package com.kkokkkok.backend.web.dto;

import com.kkokkkok.backend.domain.Inspection;
import java.time.Instant;
import java.util.UUID;

/** A lightweight snapshot of the last status recorded by the API for one analysis run. */
public record InspectionHistoryItem(UUID inspectionId, UUID assetId, String status,
                                    Instant requestedAt, Instant updatedAt) {
    public static InspectionHistoryItem from(Inspection inspection) {
        return new InspectionHistoryItem(inspection.getInspectionId(), inspection.getAssetId(),
                inspection.getStatus(), inspection.getCreatedAt(), inspection.getUpdatedAt());
    }
}
