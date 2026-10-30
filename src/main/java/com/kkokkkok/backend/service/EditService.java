package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.client.AnalysisClient;
import com.kkokkkok.backend.client.EditClient;
import com.kkokkkok.backend.domain.EditSubmission;
import com.kkokkkok.backend.domain.Inspection;
import com.kkokkkok.backend.repository.InspectionRepository;
import com.kkokkkok.backend.web.dto.EditRequest;
import com.kkokkkok.backend.web.dto.EditResponse;
import com.kkokkkok.backend.web.dto.InspectionRequest;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class EditService {
    private final InspectionRepository inspections;
    private final AnalysisClient analysisClient;
    private final EditClient editClient;
    private final ImageAssetService assets;
    private final EditStore store;
    private final InspectionService inspectionService;

    public EditService(InspectionRepository inspections, AnalysisClient analysisClient, EditClient editClient,
                       ImageAssetService assets, EditStore store, InspectionService inspectionService) {
        this.inspections = inspections;
        this.analysisClient = analysisClient;
        this.editClient = editClient;
        this.assets = assets;
        this.store = store;
        this.inspectionService = inspectionService;
    }

    public EditResponse create(EditRequest request, UUID editId) {
        List<String> cueIds = normalizeCueIds(request);
        String preservePrompt = request.preservePrompt() == null || request.preservePrompt().isBlank()
                ? null : request.preservePrompt().trim();
        String fingerprint = fingerprint(request.inspectionId(), cueIds, preservePrompt);

        var existing = store.find(editId);
        if (existing.isPresent()) {
            if (!existing.get().getFingerprint().equals(fingerprint))
                throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "같은 요청 키로 다른 편집을 요청할 수 없습니다.");
            return EditResponse.from(existing.get());
        }

        Inspection inspection = inspections.findByInspectionId(request.inspectionId())
                .orElseThrow(() -> new ApiFailure(404, "INSPECTION_NOT_FOUND", "분석 작업을 찾을 수 없습니다."));
        if (inspection.getAssetId() == null)
            throw new ApiFailure(409, "SOURCE_ASSET_MISSING", "원본 사진과 연결된 분석 결과가 아닙니다.");

        Map<String, Object> analysis = analysisClient.get(request.inspectionId());
        if (analysis == null) throw new ApiFailure(502, "INVALID_ANALYSIS_RESPONSE", "분석 결과를 확인할 수 없습니다.");
        if (!"completed".equals(analysis.get("status")))
            throw new ApiFailure(409, "INSPECTION_NOT_COMPLETED", "분석이 완료된 뒤 편집할 수 있습니다.");
        List<Map<String, Object>> selectedCues = selectCues(analysis, cueIds);
        var source = assets.loadForAnalysis(inspection.getAssetId());

        boolean reserved;
        try {
            reserved = store.reserve(editId, fingerprint, inspection.getInspectionId(), inspection.getAssetId(), cueIds);
        } catch (DataIntegrityViolationException concurrentInsert) {
            var raced = store.find(editId).orElseThrow();
            if (!raced.getFingerprint().equals(fingerprint))
                throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "같은 요청 키로 다른 편집을 요청할 수 없습니다.");
            return EditResponse.from(raced);
        }
        if (!reserved) return EditResponse.from(store.get(editId));
        try {
            var output = editClient.edit(new EditSubmission(source.assetId(), source.bytes(), source.contentType(),
                    selectedCues, preservePrompt));
            var edited = assets.storeEditedVersion(source.assetId(), output.image(), output.contentType(), output.mock());
            store.complete(editId, edited.assetId(), output.mock(), output.providerVersion(), output.mockNotice());
            try {
                UUID verificationKey = UUID.nameUUIDFromBytes(("edit-reinspection:" + editId).getBytes(StandardCharsets.UTF_8));
                var verification = inspectionService.create(new InspectionRequest(edited.assetId()), verificationKey);
                store.attachVerification(editId, verification.inspectionId(), verification.status());
            } catch (RuntimeException verificationFailure) {
                // Keep a successful edit even when the follow-up analysis provider is unavailable.
                store.verificationFailed(editId);
            }
            return EditResponse.from(store.get(editId));
        } catch (RuntimeException failure) {
            store.fail(editId);
            throw failure;
        }
    }

    public EditResponse get(UUID editId) { return EditResponse.from(store.get(editId)); }

    private List<String> normalizeCueIds(EditRequest request) {
        if (request == null || request.inspectionId() == null || request.cueIds() == null || request.cueIds().isEmpty())
            throw new ApiFailure(400, "INVALID_EDIT_REQUEST", "분석 ID와 수정할 단서 ID를 지정해 주세요.");
        if (request.cueIds().size() > 20) throw new ApiFailure(400, "INVALID_EDIT_REQUEST", "한 번에 최대 20개 단서까지 선택할 수 있습니다.");
        List<String> cueIds = request.cueIds().stream().map(value -> value == null ? "" : value.trim()).toList();
        if (cueIds.stream().anyMatch(String::isBlank) || cueIds.stream().anyMatch(value -> value.length() > 100)
                || cueIds.stream().distinct().count() != cueIds.size())
            throw new ApiFailure(400, "INVALID_EDIT_REQUEST", "단서 ID는 비어 있거나 중복될 수 없습니다.");
        return cueIds.stream().sorted().toList();
    }

    private List<Map<String, Object>> selectCues(Map<String, Object> analysis, List<String> requestedIds) {
        Object raw = analysis.get("riskFactors");
        if (!(raw instanceof List<?> factors)) throw new ApiFailure(409, "ANALYSIS_CUES_UNAVAILABLE", "분석 결과에서 편집할 단서를 찾을 수 없습니다.");
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Object factor : factors) {
            if (factor instanceof Map<?, ?> map && map.get("id") != null) {
                Map<String, Object> cue = new LinkedHashMap<>();
                map.forEach((key, value) -> cue.put(String.valueOf(key), value));
                byId.put(String.valueOf(map.get("id")), cue);
            }
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        for (String id : requestedIds) {
            Map<String, Object> cue = byId.get(id);
            if (cue == null) throw new ApiFailure(400, "UNKNOWN_CUE_ID", "선택한 단서가 해당 분석 결과에 없습니다.");
            selected.add(cue);
        }
        return List.copyOf(selected);
    }

    private String fingerprint(UUID inspectionId, List<String> cueIds, String preservePrompt) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeLong(inspectionId.getMostSignificantBits());
            out.writeLong(inspectionId.getLeastSignificantBits());
            for (String cueId : cueIds) out.writeUTF(cueId);
            out.writeBoolean(preservePrompt != null);
            if (preservePrompt != null) out.write(preservePrompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
