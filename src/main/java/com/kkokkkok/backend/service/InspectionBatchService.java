package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.validation.InspectionValidator;
import com.kkokkkok.backend.web.dto.InspectionBatchItemResponse;
import com.kkokkkok.backend.web.dto.InspectionBatchRequest;
import com.kkokkkok.backend.web.dto.InspectionBatchResponse;
import com.kkokkkok.backend.web.dto.InspectionRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class InspectionBatchService {
    private static final int MAX_ITEMS = 20;
    private final ImageAssetService assets;
    private final InspectionValidator validator;
    private final InspectionService inspections;
    private final InspectionBatchStore store;

    public InspectionBatchService(ImageAssetService assets, InspectionValidator validator,
            InspectionService inspections, InspectionBatchStore store) {
        this.assets = assets;
        this.validator = validator;
        this.inspections = inspections;
        this.store = store;
    }

    public InspectionBatchResponse create(InspectionBatchRequest request, UUID batchId) {
        if (request == null || request.items() == null || request.items().isEmpty() || request.items().size() > MAX_ITEMS)
            throw new ApiFailure(400, "INVALID_BATCH_SIZE", "사진은 1장 이상 20장 이하로 선택해 주세요.");
        var seen = new HashSet<UUID>();
        var fingerprints = new ArrayList<String>();
        var assetIds = new ArrayList<UUID>();
        for (var item : request.items()) {
            if (item == null || item.assetId() == null)
                throw new ApiFailure(400, "INVALID_ASSET_ID", "분석할 사진 ID를 확인해 주세요.");
            if (!seen.add(item.assetId())) throw new ApiFailure(400, "DUPLICATE_ASSET", "같은 사진을 여러 번 선택할 수 없습니다.");
            var submission = validator.validate(new InspectionRequest(item.assetId(), item.locationFallback()), assets.loadForAnalysis(item.assetId()));
            fingerprints.add(submission.fingerprint());
            assetIds.add(item.assetId());
        }
        String fingerprint = fingerprint(fingerprints);
        try {
            store.reserve(batchId, fingerprint, assetIds);
        } catch (DataIntegrityViolationException concurrentRequest) {
            store.verifyExisting(batchId, fingerprint);
        }
        var reserved = store.items(batchId);
        for (int i = 0; i < request.items().size(); i++) {
            var input = request.items().get(i);
            var row = reserved.get(i);
            if (!row.getStatus().equals("submitting")) continue;
            UUID childKey = childKey(batchId, i);
            try {
                var result = inspections.create(new InspectionRequest(input.assetId(), input.locationFallback()), childKey);
                store.submitted(row.getItemId(), result.inspectionId(), result.status(), Boolean.TRUE.equals(result.details().get("mock")));
            } catch (ApiFailure failure) {
                store.failed(row.getItemId(), failure.code, failure.getMessage());
            }
        }
        return response(batchId);
    }

    public InspectionBatchResponse get(UUID batchId) {
        var current = store.items(batchId);
        for (var item : current) {
            if (item.getInspectionId() == null || item.getStatus().equals("completed") || item.getStatus().equals("failed")) continue;
            try {
                var result = inspections.get(item.getInspectionId());
                store.refreshed(item.getItemId(), result.status(), Boolean.TRUE.equals(result.details().get("mock")));
            } catch (ApiFailure failure) {
                if (failure.status == 404) store.failed(item.getItemId(), failure.code, failure.getMessage());
                // Temporary provider errors do not erase the last known item status.
            }
        }
        return response(batchId);
    }

    private InspectionBatchResponse response(UUID batchId) {
        var items = store.items(batchId).stream().map(InspectionBatchItemResponse::from).toList();
        long failed = items.stream().filter(item -> item.status().equals("failed")).count();
        long active = items.stream().filter(item -> List.of("queued", "processing", "submitting").contains(item.status())).count();
        String status = active > 0 ? (items.stream().allMatch(item -> item.status().equals("queued")) ? "queued" : "processing")
                : failed == items.size() ? "failed" : failed > 0 ? "partial" : "completed";
        return new InspectionBatchResponse(batchId, status, items.stream().anyMatch(InspectionBatchItemResponse::mock), items);
    }

    private String fingerprint(List<String> itemFingerprints) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String item : itemFingerprints) {
                byte[] bytes = item.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private UUID childKey(UUID batchId, int position) {
        return UUID.nameUUIDFromBytes(("kkokkkok-inspection-batch:" + batchId + ":" + position).getBytes(StandardCharsets.UTF_8));
    }
}
