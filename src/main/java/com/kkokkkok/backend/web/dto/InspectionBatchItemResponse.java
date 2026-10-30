package com.kkokkkok.backend.web.dto;

import com.kkokkkok.backend.domain.InspectionBatchItem;
import java.time.Instant;
import java.util.UUID;

public record InspectionBatchItemResponse(UUID assetId, UUID inspectionId, String status,
        String errorCode, String errorMessage, boolean mock, Instant requestedAt, Instant updatedAt) {
    public static InspectionBatchItemResponse from(InspectionBatchItem item) {
        return new InspectionBatchItemResponse(item.getAssetId(), item.getInspectionId(), item.getStatus(),
                item.getErrorCode(), item.getErrorMessage(), item.isMock(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
