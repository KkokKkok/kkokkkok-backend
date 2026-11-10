package com.kkokkkok.backend.service;

import com.kkokkkok.backend.repository.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final ImageAssetRepository assets;
    private final InspectionRepository inspections;
    private final EditRunRepository edits;
    private final SensitivePlaceRepository places;
    private final CumulativeRunRepository cumulative;
    private final PilotOwnership ownership;
    public ProfileService(ImageAssetRepository assets, InspectionRepository inspections, EditRunRepository edits,
                          SensitivePlaceRepository places, CumulativeRunRepository cumulative, PilotOwnership ownership) {
        this.assets=assets; this.inspections=inspections; this.edits=edits;
        this.places=places; this.cumulative=cumulative; this.ownership=ownership;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> overview(UUID installationId) {
        UUID authenticated = ownership.current();
        UUID owner = authenticated == null ? installationId : authenticated;
        var ownedAssets = authenticated == null ? assets.findAll() : assets.findAllByOwnerInstallationIdOrderByCreatedAtDesc(owner);
        var originalIds = ownedAssets.stream().filter(a -> "original".equals(a.getAssetRole()))
                .map(a -> a.getId()).collect(Collectors.toSet());
        var ownedPlaces = places.findAllByInstallationIdOrderByCreatedAtAsc(owner);
        var placeIds = ownedPlaces.stream().map(p -> p.getId()).collect(Collectors.toSet());
        long checks = inspections.findAll().stream().filter(i -> originalIds.contains(i.getAssetId()) && "completed".equals(i.getStatus())).count();
        long editCount = edits.findAll().stream().filter(e -> originalIds.contains(e.getSourceAssetId()) && "completed".equals(e.getStatus())).count();
        long cumulativeCount = cumulative.findAll().stream().filter(r -> placeIds.contains(r.getPlaceId())).count();
        return Map.of("displayName", "테스터", "photoChecks", checks, "protectionEdits", editCount,
                "cumulativeChecks", cumulativeCount,
                "sensitivePlaces", ownedPlaces.stream().map(p -> Map.of("name",p.getName(),"category",p.getCategory())).toList(),
                "storagePolicy", Map.of("autoDeleteOriginals",false,"retention","until_user_deletes"));
    }
}
