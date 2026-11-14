package com.kkokkkok.backend.client;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Naver Login (authorization code flow) done on the server: the client secret never leaves the backend.
 * Codes, tokens and profile bodies are never stored or logged.
 */
@Component
public class NaverAuthClient {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() {};
    private final RestClient auth;
    private final RestClient api;
    private final String authBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String callbackUrl;

    public NaverAuthClient(@Value("${auth.naver.client-id:}") String clientId,
                           @Value("${auth.naver.client-secret:}") String clientSecret,
                           @Value("${auth.naver.callback-url:http://127.0.0.1:8098/api/auth/naver/callback}") String callbackUrl,
                           @Value("${auth.naver.auth-base-url:https://nid.naver.com}") String authBaseUrl,
                           @Value("${auth.naver.api-base-url:https://openapi.naver.com}") String apiBaseUrl,
                           @Value("${auth.naver.timeout-seconds:5}") int timeoutSeconds) {
        for (String url : new String[]{callbackUrl, authBaseUrl, apiBaseUrl}) {
            URI uri = URI.create(url);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("Invalid Naver auth URL setting");
        }
        if (timeoutSeconds < 1) throw new IllegalArgumentException("auth.naver.timeout-seconds must be positive");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.auth = RestClient.builder().requestFactory(factory).baseUrl(authBaseUrl).build();
        this.api = RestClient.builder().requestFactory(factory).baseUrl(apiBaseUrl).build();
        this.authBaseUrl = authBaseUrl.replaceAll("/+$", "");
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        this.callbackUrl = callbackUrl;
    }

    public boolean configured() { return !clientId.isEmpty() && !clientSecret.isEmpty(); }

    /** Naver login page the app opens in a browser tab; Naver redirects back to the registered callback URL. */
    public String authorizeUrl(String state) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString(authBaseUrl + "/oauth2.0/authorize")
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", callbackUrl).queryParam("state", state)
                .encode().build().toUriString();
    }

    /** Exchanges the callback code for a Naver access token and reads the member id/nickname with it. */
    public NaverUser login(String code, String state) {
        requireConfigured();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("state", state);
        var token = call(() -> auth.post().uri("/oauth2.0/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(BODY));
        if (token == null || !(token.get("access_token") instanceof String accessToken) || accessToken.isBlank())
            throw new ApiFailure(401, "SOCIAL_TOKEN_INVALID", "네이버 로그인 정보를 확인할 수 없어요. 다시 로그인해 주세요.");
        try {
            var me = call(() -> api.get().uri("/v1/nid/me").header("Authorization", "Bearer " + accessToken).retrieve().body(BODY));
            if (me == null || !"00".equals(String.valueOf(me.get("resultcode"))) || !(me.get("response") instanceof Map<?, ?> profile)
                    || !(profile.get("id") instanceof String id) || id.isBlank() || id.length() > 100)
                throw new ApiFailure(401, "SOCIAL_TOKEN_INVALID", "네이버 로그인 정보를 확인할 수 없어요. 다시 로그인해 주세요.");
            String nickname = profile.get("nickname") instanceof String name && !name.isBlank() ? limit(name) : null;
            return new NaverUser(id, nickname);
        } finally {
            revoke(accessToken);
        }
    }

    /** The service keeps no Naver token: delete it right after reading the profile (best effort). */
    private void revoke(String accessToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "delete");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("access_token", accessToken);
        form.add("service_provider", "NAVER");
        try {
            auth.post().uri("/oauth2.0/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
        } catch (RestClientException ignored) {
            // The token expires on its own (1 hour); login already succeeded.
        }
    }

    private void requireConfigured() {
        if (!configured()) throw new ApiFailure(503, "AUTH_NOT_CONFIGURED", "네이버 로그인이 설정되지 않았어요.");
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
            if (status == 400 || status == 401) throw new ApiFailure(401, "SOCIAL_TOKEN_INVALID", "네이버 로그인 정보를 확인할 수 없어요. 다시 로그인해 주세요.");
            throw new ApiFailure(502, "SOCIAL_AUTH_UNAVAILABLE", "네이버 인증 서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.");
        } catch (ResourceAccessException e) {
            throw new ApiFailure(504, "SOCIAL_AUTH_TIMEOUT", "네이버 인증 서버 연결이 실패하거나 시간이 초과되었어요.");
        } catch (RestClientException e) {
            throw new ApiFailure(502, "SOCIAL_AUTH_UNAVAILABLE", "네이버 인증 응답 형식이 올바르지 않아요.");
        }
    }

    public record NaverUser(String providerUserId, String nickname) {}
}
