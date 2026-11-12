package com.kkokkkok.backend.client;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Server-side Kakao Local API caller. The REST API key never leaves the server, and queries,
 * coordinates and upstream bodies are never logged or echoed in errors.
 */
@Component
public class KakaoLocalClient {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final boolean configured;

    public KakaoLocalClient(@Value("${kakao.local.base-url:https://dapi.kakao.com}") String baseUrl,
                            @Value("${kakao.local.rest-api-key:}") String restApiKey,
                            @Value("${kakao.local.timeout-seconds:5}") int timeoutSeconds) {
        URI uri = URI.create(baseUrl);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Invalid kakao.local.base-url");
        if (timeoutSeconds < 1) throw new IllegalArgumentException("kakao.local.timeout-seconds must be positive");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        var builder = RestClient.builder().requestFactory(factory).baseUrl(baseUrl);
        configured = !restApiKey.isBlank();
        if (configured) builder.defaultHeader("Authorization", "KakaoAK " + restApiKey.trim());
        client = builder.build();
    }

    public List<Map<String, Object>> searchAddress(String query, int size) {
        return documents(call(() -> client.get().uri(b -> b.path("/v2/local/search/address.json")
                .queryParam("query", query).queryParam("size", size).build()).retrieve().body(BODY)));
    }

    public List<Map<String, Object>> searchKeyword(String query, int size) {
        return documents(call(() -> client.get().uri(b -> b.path("/v2/local/search/keyword.json")
                .queryParam("query", query).queryParam("size", size).build()).retrieve().body(BODY)));
    }

    /** Kakao expects x = longitude and y = latitude. */
    public List<Map<String, Object>> coordinateToAddress(double latitude, double longitude) {
        return documents(call(() -> client.get().uri(b -> b.path("/v2/local/geo/coord2address.json")
                .queryParam("x", longitude).queryParam("y", latitude).build()).retrieve().body(BODY)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents(Map<String, Object> body) {
        Object documents = body == null ? null : body.get("documents");
        if (!(documents instanceof List<?> list))
            throw new ApiFailure(502, "INVALID_GEOCODING_RESPONSE", "주소 검색 응답 형식이 올바르지 않아요.");
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    private Map<String, Object> call(Supplier<Map<String, Object>> action) {
        if (!configured) throw new ApiFailure(503, "GEOCODING_NOT_CONFIGURED", "주소 검색이 설정되지 않았어요.");
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429)
                throw new ApiFailure(429, "GEOCODING_RATE_LIMITED", "주소 검색 요청이 많아요. 잠시 후 다시 시도해 주세요.");
            throw new ApiFailure(502, "GEOCODING_UNAVAILABLE", "주소 검색 서비스에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.");
        } catch (ResourceAccessException e) {
            throw new ApiFailure(504, "GEOCODING_TIMEOUT", "주소 검색 연결이 실패하거나 시간이 초과되었어요.");
        } catch (RestClientException e) {
            throw new ApiFailure(502, "INVALID_GEOCODING_RESPONSE", "주소 검색 응답 형식이 올바르지 않아요.");
        }
    }
}
