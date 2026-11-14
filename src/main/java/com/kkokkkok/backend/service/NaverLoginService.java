package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.client.NaverAuthClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Server-side Naver login for the app.
 * <ol>
 *   <li>{@code start}: the app sends a PKCE-style challenge; the server returns the Naver login URL with a one-time state.</li>
 *   <li>{@code callback}: Naver redirects the browser here; the server exchanges the code with its client secret,
 *       reads the member id, and hands the app a short-lived one-time code through the app link.</li>
 *   <li>{@code exchange}: the app proves it started the login (code verifier) and receives service tokens.</li>
 * </ol>
 * Pending logins live in memory for a few minutes only (single backend instance); a restart just asks the user to retry.
 */
@Service
public class NaverLoginService {
    static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    static final Duration HANDOFF_LIFETIME = Duration.ofMinutes(2);
    private static final int MAX_PENDING = 1_000;
    private static final String CHALLENGE = "[A-Za-z0-9_-]{43}";
    private final NaverAuthClient naver;
    private final AuthService auth;
    private final String appRedirectUri;
    private final String appPackage;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Handoff> handoffs = new ConcurrentHashMap<>();

    @Autowired
    public NaverLoginService(NaverAuthClient naver, AuthService auth,
                             @Value("${auth.naver.app-redirect-uri:kkokkkok://naver-login}") String appRedirectUri,
                             @Value("${auth.naver.app-package:com.kkokkkok.android}") String appPackage) {
        this(naver, auth, appRedirectUri, appPackage, Clock.systemUTC());
    }

    NaverLoginService(NaverAuthClient naver, AuthService auth, String appRedirectUri, String appPackage, Clock clock) {
        if (!appRedirectUri.matches("[a-z][a-z0-9+.-]*://[A-Za-z0-9.-]+") || appRedirectUri.startsWith("http"))
            throw new IllegalArgumentException("auth.naver.app-redirect-uri must be an app scheme like kkokkkok://naver-login");
        if (!appPackage.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("Invalid auth.naver.app-package");
        this.naver = naver; this.auth = auth; this.appRedirectUri = appRedirectUri; this.appPackage = appPackage; this.clock = clock;
    }

    public Map<String, Object> start(String codeChallenge, UUID installationId) {
        if (codeChallenge == null || !codeChallenge.matches(CHALLENGE))
            throw new ApiFailure(400, "INVALID_CODE_CHALLENGE", "로그인 요청 형식이 올바르지 않아요. 앱을 다시 실행해 주세요.");
        if (!naver.configured()) throw new ApiFailure(503, "AUTH_NOT_CONFIGURED", "네이버 로그인이 설정되지 않았어요.");
        purgeExpired();
        if (pending.size() >= MAX_PENDING)
            throw new ApiFailure(429, "TOO_MANY_LOGIN_ATTEMPTS", "로그인 요청이 많아요. 잠시 후 다시 시도해 주세요.");
        String state = randomToken();
        pending.put(state, new Pending(codeChallenge, installationId, clock.instant().plus(STATE_LIFETIME)));
        var response = new LinkedHashMap<String, Object>();
        response.put("authorizeUrl", naver.authorizeUrl(state));
        response.put("expiresInSeconds", STATE_LIFETIME.toSeconds());
        return response;
    }

    /** Browser callback. Always answers with an app link (success code or error), never with tokens. */
    public AppReturn callback(String code, String state, String error) {
        Pending login = state == null ? null : pending.remove(state);
        if (login == null || login.expiresAt().isBefore(clock.instant())) return appReturn("error", "expired");
        if (error != null && !error.isBlank()) return appReturn("error", "cancelled");
        if (code == null || code.isBlank() || code.length() > 512) return appReturn("error", "failed");
        NaverAuthClient.NaverUser user;
        try {
            user = naver.login(code, state);
        } catch (ApiFailure failure) {
            return appReturn("error", "failed");
        }
        String handoff = randomToken();
        handoffs.put(handoff, new Handoff(user, login.codeChallenge(), login.installationId(), clock.instant().plus(HANDOFF_LIFETIME)));
        return appReturn("code", handoff);
    }

    public Map<String, Object> exchange(String code, String codeVerifier) {
        Handoff handoff = code == null ? null : handoffs.remove(code);
        if (handoff == null || handoff.expiresAt().isBefore(clock.instant()) || codeVerifier == null
                || !codeVerifier.matches("[A-Za-z0-9._~-]{43,128}")
                || !MessageDigest.isEqual(challenge(codeVerifier).getBytes(StandardCharsets.US_ASCII),
                        handoff.codeChallenge().getBytes(StandardCharsets.US_ASCII)))
            throw new ApiFailure(401, "SOCIAL_LOGIN_EXPIRED", "네이버 로그인 시간이 지났어요. 다시 로그인해 주세요.");
        return auth.loginVerified(new AuthService.SocialUser(AuthService.NAVER, handoff.user().providerUserId(),
                handoff.user().nickname(), false), handoff.installationId());
    }

    private AppReturn appReturn(String key, String value) {
        String query = key + "=" + value;
        String scheme = appRedirectUri.substring(0, appRedirectUri.indexOf("://"));
        String hostAndQuery = appRedirectUri.substring(scheme.length() + 3) + "?" + query;
        // intent:// pins the target package in Chrome-based browsers; the plain link is the fallback button.
        return new AppReturn(appRedirectUri + "?" + query,
                "intent://" + hostAndQuery + "#Intent;scheme=" + scheme + ";package=" + appPackage + ";end", key.equals("code"));
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        pending.values().removeIf(p -> p.expiresAt().isBefore(now));
        handoffs.values().removeIf(h -> h.expiresAt().isBefore(now));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Pending(String codeChallenge, UUID installationId, Instant expiresAt) {}
    private record Handoff(NaverAuthClient.NaverUser user, String codeChallenge, UUID installationId, Instant expiresAt) {}
    public record AppReturn(String appLink, String intentLink, boolean success) {}
}
