package com.kkokkkok.backend;

import com.kkokkkok.backend.client.StubAnalysisClient;
import com.kkokkkok.backend.domain.InspectionSubmission;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StubAnalysisClientTest {
    @Test void returnsClearlyMarkedSyntheticResultForSameRequestId() {
        var client = new StubAnalysisClient();
        UUID requestId = UUID.randomUUID();
        var submission = new InspectionSubmission(UUID.randomUUID(),new byte[]{1},"image/jpeg",
                null,null,null,"synthetic-fingerprint");

        var queued = client.create(submission, requestId);
        var completed = client.get(requestId);

        assertEquals(requestId.toString(), queued.get("inspectionId"));
        assertEquals("queued", queued.get("status"));
        assertTrue((Boolean) queued.get("mock"));
        assertEquals("completed", completed.get("status"));
        assertTrue((Boolean) completed.get("mock"));
        assertEquals("모의 예시 결과이며 실제 사진을 분석하지 않았습니다.", completed.get("mockNotice"));
        assertEquals("stub-v1", completed.get("pipelineVersion"));
        assertEquals("completed", client.create(submission, requestId).get("status"));
    }

    @Test void unknownJobReturnsNotFound() {
        var client = new StubAnalysisClient();
        var failure = assertThrows(com.kkokkkok.backend.apiPayload.exception.ApiFailure.class,
                () -> client.get(UUID.randomUUID()));
        assertEquals(404, failure.status);
    }
}
