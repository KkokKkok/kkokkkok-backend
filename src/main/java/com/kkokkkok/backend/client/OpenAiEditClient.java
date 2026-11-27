package com.kkokkkok.backend.client;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.EditSubmission;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.ParameterizedTypeReference;

@Component
@ConditionalOnProperty(prefix = "editing", name = "provider", havingValue = "openai")
class OpenAiEditClient implements EditClient {
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final String quality;
    private final EditImagePreparer imagePreparer;
    private final EditPromptBuilder promptBuilder;
    private final EditImagePreparer.Framing framing;

    OpenAiEditClient(@Value("${editing.openai.api-key:}") String apiKey,
                     @Value("${editing.openai.model:gpt-image-2.5-sunburst}") String model,
                     @Value("${editing.openai.quality:high}") String quality,
                     @Value("${editing.openai.timeout-seconds:300}") int timeoutSeconds,
                     @Value("${editing.openai.framing:crop}") String framing,
                     EditImagePreparer imagePreparer,
                     EditPromptBuilder promptBuilder) {
        this.apiKey = apiKey;
        this.model = model;
        this.quality = quality;
        this.imagePreparer = imagePreparer;
        this.promptBuilder = promptBuilder;
        this.framing = EditImagePreparer.Framing.valueOf(framing.trim().toUpperCase(java.util.Locale.ROOT));
        if (timeoutSeconds < 1) throw new IllegalArgumentException("editing.openai.timeout-seconds must be positive");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().baseUrl("https://api.openai.com").requestFactory(factory).build();
    }

    @Override
    public EditResult edit(EditSubmission submission) {
        PreparedEditImage prepared = imagePreparer.prepare(submission, framing);
        if (prepared.maskPng() == null) {
            byte[] sanitized = imagePreparer.sanitizeGeneratedImage(prepared.imagePng());
            return new EditResult(sanitized, "image/png", false, "metadata-sanitization-v1", null);
        }
        if (apiKey == null || apiKey.isBlank())
            throw new ApiFailure(503, "EDITING_NOT_CONFIGURED", "보호 편집 API 키가 설정되지 않았습니다.");
        String prompt = promptBuilder.build(submission, prepared.visualCues());
        byte[] responseImage = requestEdit(prepared.imagePng(), prepared.maskPng(), prompt);
        double defect = imagePreparer.defect(prepared, responseImage);
        if (defect > 1) {
            // Occasionally the editor paints a dark patch or tears a black hole instead of the surface; one more try
            // usually fixes it.
            byte[] retried = requestEdit(prepared.imagePng(), prepared.maskPng(), prompt);
            if (imagePreparer.defect(prepared, retried) < defect) responseImage = retried;
        }
        BufferedImage result = eraseReadableLines(submission, prepared, imagePreparer.blend(prepared, responseImage));
        var composed = imagePreparer.encodeResult(result);
        return new EditResult(composed.bytes(), composed.contentType(), false, model, null);
    }

    /**
     * The editor sometimes leaves some of the marked writing as it was, mostly small lines in a frame spanning the
     * whole photo. Those lines are sent once more, each group in its own close crop and all groups at the same
     * time; a group whose request fails keeps the first edit.
     */
    private BufferedImage eraseReadableLines(EditSubmission submission, PreparedEditImage prepared, BufferedImage result) {
        var focus = imagePreparer.focusRequests(prepared, result);
        var responses = focus.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> requestEdit(request.imagePng(), request.maskPng(),
                        promptBuilder.build(submission, request.cues())), FOCUS_POOL))
                .toList();
        for (int index = 0; index < focus.size(); index++) {
            try {
                result = imagePreparer.blendFocus(result, focus.get(index), responses.get(index).join());
            } catch (CompletionException | ApiFailure failed) {
                // The first edit stands for these lines.
            }
        }
        return result;
    }

    private static final ExecutorService FOCUS_POOL = Executors.newFixedThreadPool(EditImagePreparer.MAX_FOCUS_REQUESTS, task -> {
        Thread thread = new Thread(task, "edit-focus");
        thread.setDaemon(true);
        return thread;
    });

    private byte[] requestEdit(byte[] imagePng, byte[] maskPng, String prompt) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("model", model);
        parts.add("prompt", prompt);
        parts.add("quality", quality);
        parts.add("output_format", "png");
        parts.add("image[]", filePart("source.png", imagePng));
        parts.add("mask", filePart("mask.png", maskPng));
        try {
            Map<String, Object> response = client.post()
                    .uri("/v1/images/edits")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            String encoded = firstImage(response);
            if (encoded == null || encoded.isBlank())
                throw new ApiFailure(502, "INVALID_EDIT_RESPONSE", "편집 API 응답에 결과 이미지가 없습니다.");
            try {
                return Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException invalidBase64) {
                throw new ApiFailure(502, "INVALID_EDIT_RESPONSE", "편집 API 결과 이미지를 읽지 못했습니다.");
            }
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 429) throw new ApiFailure(429, "EDITING_RATE_LIMITED", "이미지 편집 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
            throw new ApiFailure(502, "EDITING_PROVIDER_FAILED", "이미지 편집 서비스가 요청을 처리하지 못했습니다.");
        } catch (ResourceAccessException failure) {
            throw new ApiFailure(504, "EDITING_PROVIDER_TIMEOUT", "이미지 편집 시간이 초과되었습니다. 같은 요청 키로 다시 확인해 주세요.");
        } catch (RestClientException failure) {
            throw new ApiFailure(502, "INVALID_EDIT_RESPONSE", "이미지 편집 서비스 응답을 읽지 못했습니다.");
        }
    }

    private HttpEntity<ByteArrayResource> filePart(String name, byte[] bytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        };
        return new HttpEntity<>(resource, headers);
    }

    private String firstImage(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof List<?> images) || images.isEmpty()) return null;
        if (!(images.get(0) instanceof Map<?, ?> image)) return null;
        Object encoded = image.get("b64_json");
        return encoded instanceof String text ? text : null;
    }
}
