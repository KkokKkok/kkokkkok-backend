package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.Inspection;
import com.kkokkkok.backend.repository.InspectionRepository;
import com.kkokkkok.backend.web.dto.InspectionResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short DB transactions only; no network calls while holding a DB transaction. */
@Service
public class InspectionStore {
    private final InspectionRepository repository;
    public InspectionStore(InspectionRepository repository) {this.repository=repository;}
    @Transactional
    public void reserve(UUID key,String fingerprint,UUID assetId) {
        var existing=repository.findById(key);
        if(existing.isPresent()) {
            if(!existing.get().getFingerprint().equals(fingerprint)) throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT","같은 키로 다른 입력을 요청할 수 없습니다.");
        } else repository.saveAndFlush(new Inspection(key,fingerprint,assetId));
    }
    @Transactional
    public void submitted(UUID key,InspectionResponse response) {
        var entity=repository.findById(key).orElseThrow();
        if(entity.getInspectionId()!=null && !entity.getInspectionId().equals(response.inspectionId())) throw new ApiFailure(502,"INVALID_ANALYSIS_RESPONSE","분석 서버가 동일 요청에 다른 작업 ID를 반환했습니다.");
        entity.update(response.inspectionId(),response.status());
    }
    @Transactional
    public void refreshed(InspectionResponse response) {
        repository.findByInspectionId(response.inspectionId()).ifPresent(entity->entity.update(response.inspectionId(),response.status()));
    }

    @Transactional(readOnly = true)
    public UUID assetIdFor(UUID inspectionId) {
        return repository.findByInspectionId(inspectionId).map(Inspection::getAssetId).orElse(null);
    }
}
