package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.PilotInstallation;
import com.kkokkkok.backend.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PilotSessionService {
    private final PilotInviteRepository invites;
    private final PilotInstallationRepository installations;
    private final SecureRandom random = new SecureRandom();
    public PilotSessionService(PilotInviteRepository invites, PilotInstallationRepository installations) {
        this.invites = invites; this.installations = installations;
    }
    @Transactional
    public Enrollment enroll(String code) {
        String normalized = code == null ? "" : code.replace("-", "").trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z2-7]{20}")) throw invalidInvite();
        Instant now = Instant.now();
        var invite = invites.lockByHash(hash(normalized)).orElseThrow(this::invalidInvite);
        if (!invite.available(now)) throw invalidInvite();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = "kkp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var installation = new PilotInstallation(UUID.randomUUID(), hash(token), now.plus(180, ChronoUnit.DAYS));
        invite.consume();
        installations.saveAndFlush(installation);
        return new Enrollment(installation.getId(), token, installation.getExpiresAt());
    }
    @Transactional(readOnly = true)
    public PilotInstallation authenticate(String authorization) {
        if (authorization == null || !authorization.matches("Bearer kkp_[A-Za-z0-9_-]{43}")) throw unauthorized();
        var installation = installations.findByTokenHash(hash(authorization.substring(7))).orElseThrow(this::unauthorized);
        if (!installation.available(Instant.now())) throw unauthorized();
        return installation;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private ApiFailure invalidInvite() { return new ApiFailure(403, "INVALID_INVITE", "초대 코드가 올바르지 않거나 사용 기간·횟수가 끝났어요."); }
    private ApiFailure unauthorized() { return new ApiFailure(401, "PILOT_REGISTRATION_REQUIRED", "초대 코드로 이 기기를 등록해 주세요."); }
    public record Enrollment(UUID installationId, String token, Instant expiresAt) {
        @Override public String toString() { return "Enrollment[redacted]"; }
    }
}
