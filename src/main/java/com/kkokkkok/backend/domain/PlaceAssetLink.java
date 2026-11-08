package com.kkokkkok.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** A photo can be linked to more than one private place. */
@Entity
@Table(name = "place_asset_links", uniqueConstraints =
        @UniqueConstraint(name = "uk_place_asset_pair", columnNames = {"place_id", "asset_id"}))
public class PlaceAssetLink {
    @Id private UUID id;
    @Column(name = "place_id", nullable = false) private UUID placeId;
    @Column(name = "asset_id", nullable = false) private UUID assetId;
    @Column(nullable = false, updatable = false) private Instant createdAt;

    protected PlaceAssetLink() {}
    public PlaceAssetLink(UUID placeId, UUID assetId) {
        this.id = UUID.randomUUID();
        this.placeId = placeId;
        this.assetId = assetId;
        this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getPlaceId() { return placeId; }
    public UUID getAssetId() { return assetId; }
    public Instant getCreatedAt() { return createdAt; }
}
