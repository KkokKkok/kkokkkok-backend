package com.kkokkkok.backend.client;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.InspectionSubmission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only analysis driver. It returns fixed synthetic clues and never inspects image bytes.
 */
@Component
@ConditionalOnProperty(prefix="analysis", name="provider", havingValue="stub", matchIfMissing=true)
public class StubAnalysisClient implements AnalysisClient {
    private static final String PIPELINE_VERSION = "stub-v1";
    private final Map<UUID, Map<String, Object>> results = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> create(InspectionSubmission submission, UUID requestId) {
        Map<String, Object> completed = completedResult(requestId);
        Map<String, Object> existing = results.putIfAbsent(requestId, completed);
        return existing == null ? queuedResult(requestId) : copyResult(existing);
    }

    @Override
    public Map<String, Object> get(UUID inspectionId) {
        Map<String, Object> result = results.get(inspectionId);
        if (result == null) {
            throw new ApiFailure(404, "ANALYSIS_NOT_FOUND", "분석 작업을 찾을 수 없습니다.");
        }
        return copyResult(result);
    }

    private Map<String, Object> queuedResult(UUID id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inspectionId", id.toString());
        body.put("status", "queued");
        body.put("mock", true);
        body.put("pipelineVersion", PIPELINE_VERSION);
        Map<String, Object> riskSummary = new LinkedHashMap<>();
        riskSummary.put("score", null);
        riskSummary.put("level", "pending");
        body.put("riskSummary", riskSummary);
        body.put("riskFactors", List.of());
        return body;
    }

    private Map<String, Object> completedResult(UUID id) {
        Map<String, Object> cue = new LinkedHashMap<>();
        cue.put("id", "stub-clue-1");
        cue.put("type", "sign_text");
        cue.put("title", "간판 글자 (모의 예시)");
        cue.put("description", "실제 사진을 분석하지 않은 예시 단서입니다.");
        cue.put("text", "합성 예시 상호");
        cue.put("bbox", List.of(0.12, 0.20, 0.42, 0.38));
        cue.put("coordinateSpace", "analysis-normalized");
        cue.put("sources", List.of("stub"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inspectionId", id.toString());
        body.put("status", "completed");
        body.put("mock", true);
        body.put("pipelineVersion", PIPELINE_VERSION);
        body.put("riskSummary", Map.of("score", 42, "level", "caution"));
        body.put("riskFactors", List.of(cue));
        body.put("mockNotice", "모의 예시 결과이며 실제 사진을 분석하지 않았습니다.");
        return body;
    }

    private Map<String, Object> copyResult(Map<String, Object> result) {
        Map<String, Object> copy = new LinkedHashMap<>(result);
        Object factors = copy.get("riskFactors");
        if (factors instanceof List<?> list) {
            copy.put("riskFactors", new ArrayList<>(list));
        }
        return copy;
    }
}
