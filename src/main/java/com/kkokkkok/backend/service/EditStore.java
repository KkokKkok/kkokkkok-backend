package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.EditRun;
import com.kkokkkok.backend.repository.EditRunRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EditStore {
    private final EditRunRepository repository;
    public EditStore(EditRunRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public Optional<EditRun> find(UUID id) { return repository.findById(id); }

    @Transactional
    public boolean reserve(UUID id, String fingerprint, UUID inspectionId, UUID sourceAssetId, List<String> cueIds) {
        var existing = repository.findById(id);
        if (existing.isPresent()) return assertSame(existing.get(), fingerprint);
        repository.saveAndFlush(new EditRun(id, fingerprint, inspectionId, sourceAssetId, cueIds));
        return true;
    }

    @Transactional
    public void complete(UUID id, UUID editedAssetId, boolean mock, String providerVersion, String mockNotice) {
        repository.findById(id).orElseThrow().complete(editedAssetId, mock, providerVersion, mockNotice);
    }

    @Transactional
    public void fail(UUID id) { repository.findById(id).ifPresent(EditRun::fail); }

    @Transactional(readOnly = true)
    public EditRun get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ApiFailure(404, "EDIT_NOT_FOUND", "편집 작업을 찾을 수 없습니다."));
    }

    private boolean assertSame(EditRun existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint))
            throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "같은 요청 키로 다른 편집을 요청할 수 없습니다.");
        return false;
    }
}
