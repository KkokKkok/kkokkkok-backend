package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.client.AnalysisClient;
import com.kkokkkok.backend.repository.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** External cleanup is retryable; DB metadata is retained until every private file is gone. */
@Service
public class DataDeletionService {
    private final ImageAssetRepository assets;
    private final InspectionRepository inspections;
    private final EditRunRepository edits;
    private final InspectionBatchRepository batches;
    private final InspectionBatchItemRepository items;
    private final SensitivePlaceRepository places;
    private final PlaceAssetLinkRepository links;
    private final CumulativeRunRepository cumulative;
    private final PilotOwnership ownership;
    private final AssetStorage storage;
    private final AnalysisClient analysis;
    private final TransactionTemplate transaction;
    public DataDeletionService(ImageAssetRepository assets, InspectionRepository inspections, EditRunRepository edits,
            InspectionBatchRepository batches, InspectionBatchItemRepository items, SensitivePlaceRepository places,
            PlaceAssetLinkRepository links, CumulativeRunRepository cumulative, PilotOwnership ownership,
            AssetStorage storage, AnalysisClient analysis, org.springframework.transaction.PlatformTransactionManager manager) {
        this.assets=assets; this.inspections=inspections; this.edits=edits; this.batches=batches; this.items=items;
        this.places=places; this.links=links; this.cumulative=cumulative; this.ownership=ownership;
        this.storage=storage; this.analysis=analysis; this.transaction=new TransactionTemplate(manager);
    }
    public void delete(UUID installationId) {
        UUID authenticated=ownership.current(), owner=authenticated == null ? installationId : authenticated;
        var selected=authenticated == null ? assets.findAll() : assets.findAllByOwnerInstallationIdOrderByCreatedAtDesc(owner);
        var assetIds=selected.stream().map(a -> a.getId()).collect(Collectors.toSet());
        var selectedInspections=inspections.findAll().stream().filter(i -> authenticated == null || assetIds.contains(i.getAssetId())).toList();
        var selectedEdits=edits.findAll().stream().filter(e -> authenticated == null || assetIds.contains(e.getSourceAssetId())).toList();
        if (selectedEdits.stream().anyMatch(e -> !Set.of("completed","failed").contains(e.getStatus()))) throw busy();
        // Check all jobs first. Spring may not have polled a job after Node finished it.
        for (var inspection:selectedInspections) {
            try {
                var remote=analysis.get(analysisId(inspection));
                if(!Set.of("completed","failed").contains(String.valueOf(remote.get("status")))) throw busy();
            } catch(ApiFailure failure) { if(failure.status != 404) throw failure; }
        }
        var selectedPlaces=places.findAllByInstallationIdOrderByCreatedAtAsc(owner);
        var placeIds=selectedPlaces.stream().map(p -> p.getId()).collect(Collectors.toSet());
        try {
            for(var inspection:selectedInspections) analysis.delete(analysisId(inspection));
            for(var asset:selected) storage.purge(asset.getStorageKey());
        } catch(ApiFailure failure) { throw failure; }
        catch(RuntimeException failure) {
            throw new ApiFailure(503,"DATA_DELETION_INCOMPLETE","일부 자료를 삭제하지 못했어요. 다시 시도해 주세요.");
        }
        transaction.executeWithoutResult(status -> {
            // Remove links to deleted assets even when a local shared photo belonged to another test place.
            links.deleteAll(links.findAll().stream().filter(l -> placeIds.contains(l.getPlaceId()) || assetIds.contains(l.getAssetId())).toList());
            cumulative.deleteAll(cumulative.findAll().stream().filter(r -> placeIds.contains(r.getPlaceId())).toList());
            places.deleteAll(selectedPlaces);
            var selectedItems=items.findAll().stream().filter(i -> authenticated == null || assetIds.contains(i.getAssetId())).toList();
            var batchIds=selectedItems.stream().map(i -> i.getBatchId()).collect(Collectors.toSet());
            items.deleteAll(selectedItems); items.flush();
            for(UUID batchId:batchIds) if(items.findAllByBatchIdOrderByPosition(batchId).isEmpty()) batches.deleteById(batchId);
            edits.deleteAll(selectedEdits); inspections.deleteAll(selectedInspections); assets.deleteAll(selected);
        });
    }
    private UUID analysisId(com.kkokkkok.backend.domain.Inspection inspection) { return inspection.getInspectionId()==null ? inspection.getRequestId() : inspection.getInspectionId(); }
    private ApiFailure busy() { return new ApiFailure(409,"DATA_IN_USE","분석·편집이 끝난 뒤 다시 삭제해 주세요."); }
}
