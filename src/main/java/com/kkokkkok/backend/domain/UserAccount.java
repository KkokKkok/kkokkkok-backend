package com.kkokkkok.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Service user. Its id is the data owner id for photos, analyses, edits, places and cumulative runs. */
@Entity
@Table(name = "user_accounts")
public class UserAccount {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String displayName;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant lastLoginAt;
    protected UserAccount() {}
    public UserAccount(UUID id, String displayName, Instant now) {
        this.id = id; this.displayName = displayName; this.createdAt = now; this.lastLoginAt = now;
    }
    public UUID getId() { return id; }
    public String getDisplayName() { return displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public void loggedIn(String displayName, Instant now) {
        if (displayName != null && !displayName.isBlank()) this.displayName = displayName;
        this.lastLoginAt = now;
    }
}
