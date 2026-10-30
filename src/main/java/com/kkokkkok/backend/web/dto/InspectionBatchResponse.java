package com.kkokkkok.backend.web.dto;

import java.util.List;
import java.util.UUID;

public record InspectionBatchResponse(UUID batchId, String status, boolean mock,
        List<InspectionBatchItemResponse> items) {}
