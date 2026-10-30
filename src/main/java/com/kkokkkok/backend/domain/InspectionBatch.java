package com.kkokkkok.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A lightweight grouping for independent single-photo inspections. */
@Entity
@Table(name = "inspection_batches")
public class InspectionBatch {
    @Id private UUID batchId;
    @Column(nullable = false, length = 64, updatable = false) private String fingerprint;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    protected InspectionBatch() {}
    public InspectionBatch(UUID batchId, String fingerprint) {
        this.batchId = batchId;
        this.fingerprint = fingerprint;
        this.createdAt = Instant.now();
    }
    public UUID getBatchId() { return batchId; }
    public String getFingerprint() { return fingerprint; }
    public Instant getCreatedAt() { return createdAt; }
}
