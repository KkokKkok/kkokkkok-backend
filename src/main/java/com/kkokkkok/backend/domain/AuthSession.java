package com.kkokkkok.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Service login session. Only SHA-256 hashes of the opaque tokens are stored. */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {
    @Id private UUID id;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false, unique = true, length = 64) private String accessTokenHash;
    @Column(nullable = false) private Instant accessExpiresAt;
    @Column(nullable = false, unique = true, length = 64) private String refreshTokenHash;
    @Column(length = 64) private String previousRefreshTokenHash;
    @Column(nullable = false) private Instant refreshExpiresAt;
    @Column(nullable = false) private boolean revoked;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    protected AuthSession() {}
    public AuthSession(UUID id, UUID userId, String accessTokenHash, Instant accessExpiresAt,
                       String refreshTokenHash, Instant refreshExpiresAt, Instant now) {
        this.id = id; this.userId = userId; this.accessTokenHash = accessTokenHash; this.accessExpiresAt = accessExpiresAt;
        this.refreshTokenHash = refreshTokenHash; this.refreshExpiresAt = refreshExpiresAt; this.createdAt = now;
    }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Instant getAccessExpiresAt() { return accessExpiresAt; }
    public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
    public boolean accessAvailable(Instant now) { return !revoked && accessExpiresAt.isAfter(now); }
    public boolean refreshAvailable(Instant now) { return !revoked && refreshExpiresAt.isAfter(now); }
    public void rotate(String accessTokenHash, Instant accessExpiresAt, String refreshTokenHash) {
        this.previousRefreshTokenHash = this.refreshTokenHash;
        this.accessTokenHash = accessTokenHash; this.accessExpiresAt = accessExpiresAt; this.refreshTokenHash = refreshTokenHash;
    }
    public void revoke() { revoked = true; }
}
