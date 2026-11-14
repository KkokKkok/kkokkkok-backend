package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.CumulativeAnalysisService;
import com.kkokkkok.backend.web.dto.PlaceAssetRequest;
import com.kkokkkok.backend.web.dto.SensitivePlaceRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/places")
public class SensitivePlaceController {
    /** Place responses can carry the owner's coordinates in login mode; never let a proxy or client cache them. */
    private static final CacheControl NO_STORE = CacheControl.noStore();
    private final CumulativeAnalysisService service;
    public SensitivePlaceController(CumulativeAnalysisService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Installation-Id") UUID installationId,
            @Valid @RequestBody SensitivePlaceRequest request) {
        Map<String, Object> result = service.createPlace(installationId, request.name(), request.category(),
                request.latitude(), request.longitude(), request.radiusMeters() == null ? 300 : request.radiusMeters(),
                request.address());
        return ResponseEntity.created(URI.create("/api/places/" + result.get("placeId"))).cacheControl(NO_STORE).body(result);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestHeader("X-Installation-Id") UUID installationId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.listPlaces(installationId));
    }

    @GetMapping("/{placeId}")
    public ResponseEntity<Map<String, Object>> get(@RequestHeader("X-Installation-Id") UUID installationId,
                                                   @PathVariable UUID placeId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.getPlace(installationId, placeId));
    }

    @PutMapping("/{placeId}")
    public ResponseEntity<Map<String, Object>> update(@RequestHeader("X-Installation-Id") UUID installationId,
                                      @PathVariable UUID placeId,
                                      @Valid @RequestBody SensitivePlaceRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.updatePlace(installationId, placeId,
                request.name(), request.category(), request.latitude(), request.longitude(),
                request.radiusMeters() == null ? 300 : request.radiusMeters(), request.address()));
    }

    @DeleteMapping("/{placeId}")
    public ResponseEntity<Void> delete(@RequestHeader("X-Installation-Id") UUID installationId,
                                       @PathVariable UUID placeId) {
        service.deletePlace(installationId, placeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{placeId}/assets")
    public List<Map<String, Object>> assets(@RequestHeader("X-Installation-Id") UUID installationId,
                                            @PathVariable UUID placeId) {
        return service.listPlaceAssets(installationId, placeId);
    }

    @PostMapping("/{placeId}/assets")
    public Map<String, Object> link(@RequestHeader("X-Installation-Id") UUID installationId,
                                    @PathVariable UUID placeId, @Valid @RequestBody PlaceAssetRequest request) {
        return service.linkAsset(installationId, placeId, request.assetId());
    }

    @DeleteMapping("/{placeId}/assets/{assetId}")
    public ResponseEntity<Void> unlink(@RequestHeader("X-Installation-Id") UUID installationId,
                                       @PathVariable UUID placeId, @PathVariable UUID assetId) {
        service.unlinkAsset(installationId, placeId, assetId);
        return ResponseEntity.noContent().build();
    }
}
