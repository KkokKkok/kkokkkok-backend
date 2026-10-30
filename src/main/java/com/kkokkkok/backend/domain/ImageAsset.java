package com.kkokkkok.backend.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Gallery asset metadata. The image bytes are stored outside the database. */
@Entity
@Table(name = "image_assets")
public class ImageAsset {
    @Id
    private UUID id;

    @Column(nullable = false, length = 32)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 160)
    private String storageKey;

    @Column(nullable = false, length = 16)
    private String assetRole;

    private UUID parentAssetId;

    @Column(nullable = false)
    private boolean mock;

    // Coordinates are deliberately omitted from public DTOs; they may expose a sensitive location.
    private Double latitude;
    private Double longitude;
    private LocalDateTime capturedAt;

    @Column(nullable = false, length = 24)
    private String locationStatus;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "image_asset_labels", joinColumns = @JoinColumn(name = "asset_id"))
    @Column(name = "label", nullable = false, length = 40)
    private List<String> labels = new ArrayList<>();

    protected ImageAsset() {}

    public ImageAsset(UUID id, String contentType, long sizeBytes, String storageKey,
                      Double latitude, Double longitude, LocalDateTime capturedAt,
                      String locationStatus, List<String> labels, String assetRole,
                      UUID parentAssetId, boolean mock) {
        this.id = id;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.assetRole = assetRole;
        this.parentAssetId = parentAssetId;
        this.mock = mock;
        this.latitude = latitude;
        this.longitude = longitude;
        this.capturedAt = capturedAt;
        this.locationStatus = locationStatus;
        this.labels = new ArrayList<>(labels);
        this.createdAt = Instant.now();
    }

    public void replaceLabels(List<String> labels) { this.labels = new ArrayList<>(labels); }
    public UUID getId() { return id; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public String getAssetRole() { return assetRole; }
    public UUID getParentAssetId() { return parentAssetId; }
    public boolean isMock() { return mock; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public String getLocationStatus() { return locationStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public List<String> getLabels() { return List.copyOf(labels); }
}
