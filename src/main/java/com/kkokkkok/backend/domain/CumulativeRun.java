package com.kkokkkok.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable snapshot of one cumulative-analysis execution. */
@Entity
@Table(name = "cumulative_runs")
public class CumulativeRun {
    @Id private UUID id;
    @Column(nullable = false) private UUID placeId;
    @Column(nullable = false, length = 80) private String algorithmVersion;
    @Lob @Column(nullable = false) private String resultJson;
    @Column(nullable = false, updatable = false) private Instant createdAt;

    protected CumulativeRun() {}
    public CumulativeRun(UUID id, UUID placeId, String algorithmVersion, String resultJson) {
        this.id = id;
        this.placeId = placeId;
        this.algorithmVersion = algorithmVersion;
        this.resultJson = resultJson;
        this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getPlaceId() { return placeId; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public String getResultJson() { return resultJson; }
    public Instant getCreatedAt() { return createdAt; }
}
