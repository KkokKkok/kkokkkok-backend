package com.kkokkkok.backend.web.dto;

import com.kkokkkok.backend.domain.ImageAsset;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Public gallery response intentionally excludes raw GPS coordinates and storage keys. */
public record AssetResponse(UUID assetId, String contentType, long sizeBytes,
                            String locationStatus, LocalDateTime capturedAt,
                            List<String> labels, Instant createdAt) {
    public static AssetResponse from(ImageAsset asset) {
        return new AssetResponse(asset.getId(), asset.getContentType(), asset.getSizeBytes(),
                asset.getLocationStatus(), asset.getCapturedAt(), asset.getLabels(), asset.getCreatedAt());
    }
}
