package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.client.KakaoAuthClient;
import com.kkokkkok.backend.domain.AuthSession;
import com.kkokkkok.backend.domain.SocialAccount;
import com.kkokkkok.backend.domain.UserAccount;
import com.kkokkkok.backend.repository.AuthSessionRepository;
import com.kkokkkok.backend.repository.ImageAssetRepository;
import com.kkokkkok.backend.repository.SensitivePlaceRepository;
import com.kkokkkok.backend.repository.SocialAccountRepository;
import com.kkokkkok.backend.repository.UserAccountRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Social login → service user → opaque access/refresh token session with rotation. */
@Service
public class AuthService {
    public static final String KAKAO = "kakao";
    private static final String ACCESS_PREFIX = "kka_";
    private static final String REFRESH_PREFIX = "kkr_";
    private static final String DEFAULT_NAME = "꼭꼭 사용자";
    private final KakaoAuthClient kakao;
    private final UserAccountRepository users;
    private final SocialAccountRepository socialAccounts;
    private final AuthSessionRepository sessions;
    private final SensitivePlaceRepository places;
    private final ImageAssetRepository assets;
    private final TransactionTemplate transaction;
    private final Duration accessLifetime;
    private final Duration refreshLifetime;
    private final boolean linkInstallationData;
    private final SecureRandom random = new SecureRandom();

    public AuthService(KakaoAuthClient kakao, UserAccountRepository users, SocialAccountRepository socialAccounts,
                       AuthSessionRepository sessions, SensitivePlaceRepository places, ImageAssetRepository assets,
                       PlatformTransactionManager manager,
                       @Value("${auth.access-token-minutes:60}") long accessMinutes,
                       @Value("${auth.refresh-token-days:30}") long refreshDays,
                       @Value("${auth.link-installation-data:false}") boolean linkInstallationData) {
        if (accessMinutes < 1 || refreshDays < 1) throw new IllegalArgumentException("Token lifetimes must be positive");
        this.kakao = kakao; this.users = users; this.socialAccounts = socialAccounts; this.sessions = sessions;
        this.places = places; this.assets = assets; this.transaction = new TransactionTemplate(manager);
        this.accessLifetime = Duration.ofMinutes(accessMinutes); this.refreshLifetime = Duration.ofDays(refreshDays);
        this.linkInstallationData = linkInstallationData;
    }

    public Map<String, Object> loginWithKakao(String providerAccessToken, UUID installationId) {
        // Verify with Kakao before opening a DB transaction.
        var kakaoUser = kakao.verify(providerAccessToken);
        try {
            return transaction.execute(status -> login(kakaoUser, installationId));
        } catch (DataIntegrityViolationException concurrentFirstLogin) {
            return transaction.execute(status -> login(kakaoUser, installationId));
        }
    }

    private Map<String, Object> login(KakaoAuthClient.KakaoUser kakaoUser, UUID installationId) {
        Instant now = Instant.now();
        var existing = socialAccounts.findByProviderAndProviderUserId(KAKAO, kakaoUser.providerUserId());
        boolean newUser = existing.isEmpty();
        UserAccount user;
        if (newUser) {
            user = users.saveAndFlush(new UserAccount(UUID.randomUUID(),
                    kakaoUser.nickname() == null ? DEFAULT_NAME : kakaoUser.nickname(), now));
            socialAccounts.saveAndFlush(new SocialAccount(UUID.randomUUID(), KAKAO, kakaoUser.providerUserId(), user.getId(), now));
        } else {
            user = users.findById(existing.get().getUserId()).orElseThrow(AuthService::unauthorized);
            user.loggedIn(kakaoUser.nickname(), now);
        }
        boolean linked = false;
        if (linkInstallationData && installationId != null && !installationId.equals(user.getId())
                && users.findById(installationId).isEmpty()) {
            // Development migration: data saved under this app installation (places, pilot-owned photos) moves to the user.
            linked = places.reassignOwner(installationId, user.getId()) + assets.reassignOwner(installationId, user.getId()) > 0;
        }
        String access = token(ACCESS_PREFIX), refresh = token(REFRESH_PREFIX);
        var session = sessions.saveAndFlush(new AuthSession(UUID.randomUUID(), user.getId(),
                PilotSessionService.hash(access), now.plus(accessLifetime),
                PilotSessionService.hash(refresh), now.plus(refreshLifetime), now));
        var response = tokens(access, refresh, session);
        response.put("user", user(user));
        response.put("newUser", newUser);
        response.put("installationDataLinked", linked);
        return response;
    }

    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || !refreshToken.matches(REFRESH_PREFIX + "[A-Za-z0-9_-]{43}")) throw unauthorized();
        String hash = PilotSessionService.hash(refreshToken);
        // A rotated-out refresh token presented again is treated as theft: end that session (committed before the 401).
        boolean reused = Boolean.TRUE.equals(transaction.execute(status -> {
            if (sessions.findByRefreshTokenHash(hash).isPresent()) return false;
            sessions.findByPreviousRefreshTokenHash(hash).ifPresent(session -> { session.revoke(); sessions.saveAndFlush(session); });
            return true;
        }));
        if (reused) throw unauthorized();
        return transaction.execute(status -> {
            Instant now = Instant.now();
            var session = sessions.findByRefreshTokenHash(hash).orElseThrow(AuthService::unauthorized);
            if (!session.refreshAvailable(now) || users.findById(session.getUserId()).isEmpty()) throw unauthorized();
            String access = token(ACCESS_PREFIX), refresh = token(REFRESH_PREFIX);
            session.rotate(PilotSessionService.hash(access), now.plus(accessLifetime), PilotSessionService.hash(refresh));
            sessions.saveAndFlush(session);
            var response = tokens(access, refresh, session);
            response.put("user", user(users.findById(session.getUserId()).orElseThrow(AuthService::unauthorized)));
            return response;
        });
    }

    /** Resolves a service access token to the session; used by the auth filter for every API request. */
    public AuthSession authenticate(String authorization) {
        if (authorization == null || !authorization.matches("Bearer " + ACCESS_PREFIX + "[A-Za-z0-9_-]{43}")) throw unauthorized();
        var session = sessions.findByAccessTokenHash(PilotSessionService.hash(authorization.substring(7)))
                .orElseThrow(AuthService::unauthorized);
        if (!session.accessAvailable(Instant.now())) throw unauthorized();
        return session;
    }

    public void logout(UUID sessionId) {
        transaction.executeWithoutResult(status -> sessions.findById(sessionId).ifPresent(session -> {
            session.revoke();
            sessions.saveAndFlush(session);
        }));
    }

    public Map<String, Object> me(UUID userId) {
        return user(users.findById(userId).orElseThrow(AuthService::unauthorized));
    }

    public String displayName(UUID userId) {
        return users.findById(userId).map(UserAccount::getDisplayName).orElse(null);
    }

    private static Map<String, Object> user(UserAccount user) {
        var map = new LinkedHashMap<String, Object>();
        map.put("userId", user.getId());
        map.put("displayName", user.getDisplayName());
        return map;
    }

    private static LinkedHashMap<String, Object> tokens(String access, String refresh, AuthSession session) {
        var map = new LinkedHashMap<String, Object>();
        map.put("tokenType", "Bearer");
        map.put("accessToken", access);
        map.put("accessTokenExpiresAt", session.getAccessExpiresAt());
        map.put("refreshToken", refresh);
        map.put("refreshTokenExpiresAt", session.getRefreshExpiresAt());
        return map;
    }

    private String token(String prefix) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static ApiFailure unauthorized() {
        return new ApiFailure(401, "AUTH_REQUIRED", "로그인이 필요해요. 다시 로그인해 주세요.");
    }
}
