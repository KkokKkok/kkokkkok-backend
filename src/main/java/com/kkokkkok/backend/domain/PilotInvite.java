package com.kkokkkok.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pilot_invites")
public class PilotInvite {
    @Id @Column(length = 64) private String codeHash;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false) private int remainingUses;
    protected PilotInvite() {}
    public PilotInvite(String codeHash, Instant expiresAt, int remainingUses) {
        this.codeHash = codeHash; this.expiresAt = expiresAt; this.remainingUses = remainingUses;
    }
    public boolean available(Instant now) { return expiresAt.isAfter(now) && remainingUses > 0; }
    public void consume() { remainingUses--; }
}
