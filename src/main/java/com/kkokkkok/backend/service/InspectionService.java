package com.kkokkkok.backend.service;

import com.kkokkkok.backend.client.AnalysisClient;
import com.kkokkkok.backend.converter.InspectionConverter;
import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.validation.InspectionValidator;
import com.kkokkkok.backend.web.dto.*;
import java.util.UUID;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class InspectionService {
    private final InspectionValidator validator;
    private final AnalysisClient client;
    private final InspectionConverter converter;
    private final InspectionStore store;
    private final ImageAssetService assets;
    private final PilotOwnership ownership;
    public InspectionService(InspectionValidator validator,AnalysisClient client,InspectionConverter converter,
                             InspectionStore store,ImageAssetService assets,PilotOwnership ownership) {
        this.validator=validator; this.client=client; this.converter=converter; this.store=store; this.assets=assets; this.ownership=ownership;
    }
    public InspectionResponse create(InspectionRequest request,UUID key) {
        if (request == null || request.assetId() == null) throw new ApiFailure(400,"INVALID_ASSET_ID","분석할 사진 ID를 확인해 주세요.");
        var asset=assets.loadForAnalysis(request.assetId());
        var input=validator.validate(request,asset);
        try {store.reserve(key,input.fingerprint(),input.assetId(),input.locationFallback());}
        catch(DataIntegrityViolationException concurrentInsert) {store.reserve(key,input.fingerprint(),input.assetId(),input.locationFallback());}
        // Same key is also forwarded to Node, including after a timeout or server restart.
        var result=converter.fromNode(client.create(input,key),null);
        store.submitted(key,result);
        return converter.withAssetId(result,input.assetId());
    }
    public InspectionResponse get(UUID id) {
        if (ownership.current() != null) {
            UUID assetId = store.assetIdFor(id);
            if (assetId == null) throw new ApiFailure(404,"INSPECTION_NOT_FOUND","분석 작업을 찾을 수 없습니다.");
            assets.get(assetId);
        }
        var result=converter.fromNode(client.get(id),id);
        store.refreshed(result);
        return converter.withAssetId(result,store.assetIdFor(result.inspectionId()));
    }

    public List<InspectionHistoryItem> history(UUID assetId) {
        assets.get(assetId); // Fail clearly for an unknown asset instead of returning an ambiguous empty list.
        return store.historyFor(assetId).stream().map(InspectionHistoryItem::from).toList();
    }
}
