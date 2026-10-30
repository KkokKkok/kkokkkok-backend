package com.kkokkkok.backend.web.dto;

import com.kkokkkok.backend.domain.EditRun;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EditResponse(UUID editId, UUID inspectionId, UUID sourceAssetId, UUID editedAssetId,
                           List<String> cueIds, String status, boolean mock, String mockNotice,
                           String providerVersion, Instant createdAt, Instant updatedAt) {
    public static EditResponse from(EditRun run) {
        return new EditResponse(run.getEditId(), run.getInspectionId(), run.getSourceAssetId(),
                run.getEditedAssetId(), run.getCueIds(), run.getStatus(), run.isMock(),
                run.getMockNotice(), run.getProviderVersion(), run.getCreatedAt(), run.getUpdatedAt());
    }
}
