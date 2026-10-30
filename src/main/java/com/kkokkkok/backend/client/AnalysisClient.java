package com.kkokkkok.backend.client;

import com.kkokkkok.backend.domain.InspectionSubmission;
import java.util.Map;
import java.util.UUID;

public interface AnalysisClient {
    Map<String,Object> create(InspectionSubmission submission,UUID requestId);
    Map<String,Object> get(UUID inspectionId);
}
