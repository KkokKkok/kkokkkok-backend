package com.kkokkkok.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pilot_installations")
public class PilotInstallation {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean revoked;
    protected PilotInstallation() {}
    public PilotInstallation(UUID id, String tokenHash, Instant expiresAt) {
        this.id = id; this.tokenHash = tokenHash; this.expiresAt = expiresAt;
    }
    public UUID getId() { return id; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean available(Instant now) { return !revoked && expiresAt.isAfter(now); }
    public void revoke() { revoked = true; }
}
