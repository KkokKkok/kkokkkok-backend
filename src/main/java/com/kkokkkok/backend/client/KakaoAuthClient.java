package com.kkokkkok.backend.client;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import java.net.URI;
import java.time.Duration;
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
 * Verifies a Kakao user access token obtained by the Android SDK. The token must belong to the configured
 * Kakao app (app_id), so tokens issued to other apps are rejected. Tokens are never stored or logged.
 */
@Component
public class KakaoAuthClient {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final String appId;

    public KakaoAuthClient(@Value("${auth.kakao.base-url:https://kapi.kakao.com}") String baseUrl,
                           @Value("${auth.kakao.app-id:}") String appId,
                           @Value("${auth.kakao.timeout-seconds:5}") int timeoutSeconds) {
        URI uri = URI.create(baseUrl);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Invalid auth.kakao.base-url");
        if (timeoutSeconds < 1) throw new IllegalArgumentException("auth.kakao.timeout-seconds must be positive");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
        this.appId = appId.trim();
    }

    public KakaoUser verify(String accessToken) {
        if (appId.isEmpty()) throw new ApiFailure(503, "AUTH_NOT_CONFIGURED", "카카오 로그인이 설정되지 않았어요.");
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > 4096 || !accessToken.matches("[A-Za-z0-9._~+/=-]+"))
            throw invalidToken();
        String bearer = "Bearer " + accessToken;
        var info = call(() -> client.get().uri("/v1/user/access_token_info").header("Authorization", bearer).retrieve().body(BODY));
        if (info == null || !appId.equals(String.valueOf(info.get("app_id"))) || !(info.get("id") instanceof Number id))
            throw invalidToken();
        var me = call(() -> client.get().uri("/v2/user/me").header("Authorization", bearer).retrieve().body(BODY));
        if (me == null || !(me.get("id") instanceof Number meId) || meId.longValue() != id.longValue()) throw invalidToken();
        return new KakaoUser(Long.toString(id.longValue()), nickname(me), nicknameConsentRequired(me));
    }

    @SuppressWarnings("unchecked")
    private static String nickname(Map<String, Object> me) {
        if (me.get("kakao_account") instanceof Map<?, ?> account && account.get("profile") instanceof Map<?, ?> profile
                && profile.get("nickname") instanceof String name && !name.isBlank()) return limit(name);
        if (me.get("properties") instanceof Map<?, ?> properties && properties.get("nickname") instanceof String name
                && !name.isBlank()) return limit(name);
        return null;
    }

    /** Kakao omits the nickname until the user agrees to the optional profile_nickname item. */
    private static boolean nicknameConsentRequired(Map<String, Object> me) {
        return me.get("kakao_account") instanceof Map<?, ?> account
                && Boolean.TRUE.equals(account.get("profile_nickname_needs_agreement"));
    }

    private static String limit(String value) {
        String trimmed = value.strip();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private Map<String, Object> call(Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 401) throw invalidToken();
            throw new ApiFailure(502, "SOCIAL_AUTH_UNAVAILABLE", "카카오 인증 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.");
        } catch (ResourceAccessException e) {
            throw new ApiFailure(504, "SOCIAL_AUTH_TIMEOUT", "카카오 인증 서버 연결이 실패하거나 시간이 초과되었어요.");
        } catch (RestClientException e) {
            throw new ApiFailure(502, "SOCIAL_AUTH_UNAVAILABLE", "카카오 인증 응답 형식이 올바르지 않아요.");
        }
    }

    private static ApiFailure invalidToken() {
        return new ApiFailure(401, "SOCIAL_TOKEN_INVALID", "카카오 로그인 정보를 확인할 수 없어요. 다시 로그인해 주세요.");
    }

    public record KakaoUser(String providerUserId, String nickname, boolean nicknameConsentRequired) {}
}
