package com.kkokkkok.backend.client;

import com.kkokkkok.backend.domain.InspectionSubmission;
import java.util.Map;
import java.util.UUID;

public interface AnalysisClient {
    Map<String,Object> create(InspectionSubmission submission,UUID requestId);
    Map<String,Object> get(UUID inspectionId);
    /** Stops an inspection the caller no longer waits for. One that has already ended is left as it is. */
    default void cancel(UUID inspectionId) {}
    default void delete(UUID inspectionId) { throw new UnsupportedOperationException("Analysis deletion is not configured"); }
}
