package com.kkokkkok.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A private place used only as cumulative-analysis context. */
@Entity
@Table(name = "sensitive_places")
public class SensitivePlace {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID installationId;
    @Column(nullable = false, length = 80) private String name;
    @Column(nullable = false, length = 16) private String category;
    @Column(nullable = false) private double latitude;
    @Column(nullable = false) private double longitude;
    @Column(nullable = false) private int radiusMeters;
    @Column(nullable = false, updatable = false) private Instant createdAt;

    protected SensitivePlace() {}

    public SensitivePlace(UUID id, UUID installationId, String name, String category, double latitude,
                          double longitude, int radiusMeters) {
        this.id = id;
        this.installationId = installationId;
        this.name = name;
        this.category = category;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
        this.createdAt = Instant.now();
    }

    public void update(String name, String category, double latitude, double longitude, int radiusMeters) {
        this.name = name;
        this.category = category;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
    }

    public UUID getId() { return id; }
    public UUID getInstallationId() { return installationId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getRadiusMeters() { return radiusMeters; }
    public Instant getCreatedAt() { return createdAt; }
}
