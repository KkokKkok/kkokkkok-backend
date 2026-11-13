package com.kkokkkok.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Links a provider user id (e.g. Kakao member id) to a service user. No provider tokens are stored. */
@Entity
@Table(name = "social_accounts", uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "providerUserId"}))
public class SocialAccount {
    @Id private UUID id;
    @Column(nullable = false, length = 20) private String provider;
    @Column(nullable = false, length = 100) private String providerUserId;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    protected SocialAccount() {}
    public SocialAccount(UUID id, String provider, String providerUserId, UUID userId, Instant now) {
        this.id = id; this.provider = provider; this.providerUserId = providerUserId; this.userId = userId; this.createdAt = now;
    }
    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
}
