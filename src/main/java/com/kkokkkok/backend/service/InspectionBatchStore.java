package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.InspectionBatch;
import com.kkokkkok.backend.domain.InspectionBatchItem;
import com.kkokkkok.backend.repository.InspectionBatchItemRepository;
import com.kkokkkok.backend.repository.InspectionBatchRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionBatchStore {
    private final InspectionBatchRepository batches;
    private final InspectionBatchItemRepository items;
    public InspectionBatchStore(InspectionBatchRepository batches, InspectionBatchItemRepository items) {
        this.batches = batches;
        this.items = items;
    }

    @Transactional
    public boolean reserve(UUID batchId, String fingerprint, List<UUID> assetIds) {
        var existing = batches.findById(batchId);
        if (existing.isPresent()) {
            if (!existing.get().getFingerprint().equals(fingerprint))
                throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "같은 키로 다른 사진 묶음을 요청할 수 없습니다.");
            return false;
        }
        batches.saveAndFlush(new InspectionBatch(batchId, fingerprint));
        for (int i = 0; i < assetIds.size(); i++) {
            UUID itemId = UUID.nameUUIDFromBytes((batchId + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            items.save(new InspectionBatchItem(itemId, batchId, i, assetIds.get(i)));
        }
        items.flush();
        return true;
    }

    @Transactional(readOnly = true)
    public void verifyExisting(UUID batchId, String fingerprint) {
        var existing = batches.findById(batchId)
                .orElseThrow(() -> new ApiFailure(409, "BATCH_RESERVATION_CONFLICT", "분석 묶음 요청이 겹쳤습니다. 같은 요청으로 다시 시도해 주세요."));
        if (!existing.getFingerprint().equals(fingerprint))
            throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "같은 키로 다른 사진 묶음을 요청할 수 없습니다.");
    }

    @Transactional
    public void submitted(UUID itemId, UUID inspectionId, String status, boolean mock) {
        var item = items.findById(itemId).orElseThrow();
        item.submitted(inspectionId, status, mock);
    }
    @Transactional
    public void failed(UUID itemId, String code, String message) {
        items.findById(itemId).orElseThrow().failed(code, message);
    }
    @Transactional
    public void refreshed(UUID itemId, String status, boolean mock) {
        items.findById(itemId).ifPresent(item -> item.refreshed(status, mock));
    }
    @Transactional(readOnly = true)
    public List<InspectionBatchItem> items(UUID batchId) {
        if (!batches.existsById(batchId)) throw new ApiFailure(404, "BATCH_NOT_FOUND", "분석 묶음을 찾을 수 없습니다.");
        return items.findAllByBatchIdOrderByPosition(batchId);
    }
    @Transactional(readOnly = true)
    public boolean exists(UUID batchId) { return batches.existsById(batchId); }
}
