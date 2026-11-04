package com.kkokkkok.backend.client;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.EditSubmission;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    OpenAiEditClient(@Value("${editing.openai.api-key:}") String apiKey,
                     @Value("${editing.openai.model:gpt-image-2.5-sunburst}") String model,
                     @Value("${editing.openai.quality:high}") String quality,
                     @Value("${editing.openai.timeout-seconds:300}") int timeoutSeconds,
                     EditImagePreparer imagePreparer,
                     EditPromptBuilder promptBuilder) {
        this.apiKey = apiKey;
        this.model = model;
        this.quality = quality;
        this.imagePreparer = imagePreparer;
        this.promptBuilder = promptBuilder;
        if (timeoutSeconds < 1) throw new IllegalArgumentException("editing.openai.timeout-seconds must be positive");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().baseUrl("https://api.openai.com").requestFactory(factory).build();
    }

    @Override
    public EditResult edit(EditSubmission submission) {
        PreparedEditImage prepared = imagePreparer.prepare(submission);
        if (prepared.maskPng() == null) {
            byte[] sanitized = imagePreparer.sanitizeGeneratedImage(prepared.imagePng());
            return new EditResult(sanitized, "image/png", false, "metadata-sanitization-v1", null);
        }
        if (apiKey == null || apiKey.isBlank())
            throw new ApiFailure(503, "EDITING_NOT_CONFIGURED", "보호 편집 API 키가 설정되지 않았습니다.");
        byte[] responseImage = requestEdit(prepared, promptBuilder.build(submission, prepared.visualCues()));
        byte[] sanitized = imagePreparer.sanitizeGeneratedImage(responseImage);
        return new EditResult(sanitized, "image/png", false, model, null);
    }

    private byte[] requestEdit(PreparedEditImage prepared, String prompt) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("model", model);
        parts.add("prompt", prompt);
        parts.add("quality", quality);
        parts.add("output_format", "png");
        parts.add("image[]", filePart("source.png", prepared.imagePng()));
        parts.add("mask", filePart("mask.png", prepared.maskPng()));
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
