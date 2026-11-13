package com.kkokkkok.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Append-only consent record: one row per agreement (or change) for a terms version. */
@Entity
@Table(name = "terms_agreements", indexes = @Index(name = "ix_terms_user_agreed", columnList = "userId, agreedAt"))
public class TermsAgreement {
    @Id private UUID id;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false, length = 40) private String termsVersion;
    @Column(nullable = false) private boolean serviceTerms;
    @Column(nullable = false) private boolean privacy;
    @Column(nullable = false) private boolean location;
    @Column(nullable = false) private boolean marketing;
    @Column(nullable = false, updatable = false) private Instant agreedAt;
    protected TermsAgreement() {}
    public TermsAgreement(UUID id, UUID userId, String termsVersion, boolean serviceTerms, boolean privacy,
                          boolean location, boolean marketing, Instant agreedAt) {
        this.id = id; this.userId = userId; this.termsVersion = termsVersion; this.serviceTerms = serviceTerms;
        this.privacy = privacy; this.location = location; this.marketing = marketing; this.agreedAt = agreedAt;
    }
    public String getTermsVersion() { return termsVersion; }
    public boolean isMarketing() { return marketing; }
    public Instant getAgreedAt() { return agreedAt; }
    public boolean requiredAccepted() { return serviceTerms && privacy && location; }
}
