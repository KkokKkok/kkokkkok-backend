package com.kkokkkok.backend.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.CumulativeRun;
import com.kkokkkok.backend.domain.ImageAsset;
import com.kkokkkok.backend.domain.Inspection;
import com.kkokkkok.backend.domain.PlaceAssetLink;
import com.kkokkkok.backend.domain.SensitivePlace;
import com.kkokkkok.backend.repository.CumulativeRunRepository;
import com.kkokkkok.backend.repository.ImageAssetRepository;
import com.kkokkkok.backend.repository.InspectionRepository;
import com.kkokkkok.backend.repository.PlaceAssetLinkRepository;
import com.kkokkkok.backend.repository.SensitivePlaceRepository;
import com.kkokkkok.backend.client.AnalysisClient;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A reproducible first-pass score over saved single-photo inspections. */
@Service
public class CumulativeAnalysisService {
    public static final String VERSION = "cumulative-score-v1-provisional";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SensitivePlaceRepository places;
    private final PlaceAssetLinkRepository links;
    private final ImageAssetRepository assets;
    private final InspectionRepository inspections;
    private final CumulativeRunRepository runs;
    private final ImageAssetService assetService;
    private final AnalysisClient analysisClient;
    private final PilotOwnership ownership;

    public CumulativeAnalysisService(SensitivePlaceRepository places, PlaceAssetLinkRepository links,
                                     ImageAssetRepository assets, InspectionRepository inspections,
                                     CumulativeRunRepository runs, ImageAssetService assetService,
                                     AnalysisClient analysisClient, PilotOwnership ownership) {
        this.places = places;
        this.links = links;
        this.assets = assets;
        this.inspections = inspections;
        this.runs = runs;
        this.assetService = assetService;
        this.analysisClient = analysisClient;
        this.ownership = ownership;
    }

    public List<Map<String, Object>> listPlaces(UUID installationId) {
        return places.findAllByInstallationIdOrderByCreatedAtAsc(installationId).stream()
                .map(this::placeView).toList();
    }

    @Transactional
    public Map<String, Object> createPlace(UUID installationId, String name, String category,
                                           double latitude, double longitude, Integer radiusMeters) {
        return createPlace(installationId, name, category, latitude, longitude, radiusMeters, null);
    }

    @Transactional
    public Map<String, Object> createPlace(UUID installationId, String name, String category,
                                           double latitude, double longitude, Integer radiusMeters, String address) {
        validateCoordinates(latitude, longitude);
        if (!List.of("home", "work", "school", "other").contains(category))
            throw new ApiFailure(400, "INVALID_PLACE_CATEGORY", "장소 종류를 확인해 주세요.");
        validateRadius(radiusMeters);
        SensitivePlace place = places.save(new SensitivePlace(UUID.randomUUID(), installationId,
                name.trim(), category, latitude, longitude, radiusMeters, address(address)));
        return placeView(place);
    }

    public Map<String, Object> getPlace(UUID installationId, UUID placeId) {
        return placeView(requirePlace(installationId, placeId));
    }

    @Transactional
    public Map<String, Object> updatePlace(UUID installationId, UUID placeId, String name, String category,
                                          double latitude, double longitude, Integer radiusMeters) {
        return updatePlace(installationId, placeId, name, category, latitude, longitude, radiusMeters, null);
    }

    @Transactional
    public Map<String, Object> updatePlace(UUID installationId, UUID placeId, String name, String category,
                                          double latitude, double longitude, Integer radiusMeters, String address) {
        validateCoordinates(latitude, longitude);
        if (!List.of("home", "work", "school", "other").contains(category))
            throw new ApiFailure(400, "INVALID_PLACE_CATEGORY", "장소 종류를 확인해 주세요.");
        validateRadius(radiusMeters);
        SensitivePlace place = requirePlace(installationId, placeId);
        place.update(name.trim(), category, latitude, longitude, radiusMeters, address(address));
        return placeView(place);
    }

    @Transactional
    public void deletePlace(UUID installationId, UUID placeId) {
        requirePlace(installationId, placeId);
        links.deleteAllByPlaceId(placeId);
        runs.deleteAll(runs.findAllByPlaceIdOrderByCreatedAtDesc(placeId));
        places.deleteById(placeId);
    }

    @Transactional
    public Map<String, Object> linkAsset(UUID installationId, UUID placeId, UUID assetId) {
        requirePlace(installationId, placeId);
        assetService.get(assetId);
        ImageAsset asset = assets.findById(assetId)
                .orElseThrow(() -> new ApiFailure(404, "ASSET_NOT_FOUND", "사진을 찾을 수 없습니다."));
        links.findByPlaceIdAndAssetId(placeId, assetId).orElseGet(() -> links.save(new PlaceAssetLink(placeId, assetId)));
        return assetView(asset);
    }

    @Transactional
    public void unlinkAsset(UUID installationId, UUID placeId, UUID assetId) {
        requirePlace(installationId, placeId);
        assetService.get(assetId);
        links.deleteByPlaceIdAndAssetId(placeId, assetId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPlaceAssets(UUID installationId, UUID placeId) {
        requirePlace(installationId, placeId);
        return links.findAllByPlaceIdOrderByCreatedAtDesc(placeId).stream()
                .map(link -> { assetService.get(link.getAssetId()); return assets.findById(link.getAssetId()).map(this::assetView).orElse(null); })
                .filter(java.util.Objects::nonNull).toList();
    }

    public Map<String, Object> run(UUID installationId, UUID placeId, List<UUID> assetIds) {
        SensitivePlace place = requirePlace(installationId, placeId);
        if (assetIds.isEmpty() || assetIds.size() > 100 || assetIds.stream().distinct().count() != assetIds.size())
            throw new ApiFailure(400, "INVALID_CUMULATIVE_REQUEST", "1~100장의 서로 다른 사진을 선택해 주세요.");

        assetIds.forEach(assetService::get);
        List<PhotoEvidence> photos = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (UUID assetId : assetIds) {
            if (links.findByPlaceIdAndAssetId(placeId, assetId).isEmpty())
                throw new ApiFailure(400, "ASSET_NOT_LINKED_TO_PLACE", "선택한 사진이 이 장소에 연결되어 있지 않습니다.");
            ImageAsset asset = assets.findById(assetId)
                    .orElseThrow(() -> new ApiFailure(404, "ASSET_NOT_FOUND", "사진을 찾을 수 없습니다."));
            if (asset.isMock() || !"original".equals(asset.getAssetRole())) {
                excluded.add(exclusion(assetId, "실제 원본 사진이 아니어서 누적 분석에서 제외했어요."));
                continue;
            }
            if (asset.getCaptureLatitude() == null || asset.getCaptureLongitude() == null) {
                excluded.add(exclusion(assetId, "촬영 위치가 없어 누적 분석에서 제외했어요."));
                continue;
            }
            Inspection inspection = inspections.findAllByAssetIdOrderByCreatedAtDesc(assetId).stream()
                    .filter(item -> item.getInspectionId() != null && "completed".equals(item.getStatus()))
                    .findFirst().orElse(null);
            if (inspection == null) {
                excluded.add(exclusion(assetId, "완료된 사진 검사가 없어 제외했어요."));
                continue;
            }
            Map<String, Object> analysis;
            try { analysis = analysisClient.get(inspection.getInspectionId()); }
            catch (RuntimeException failure) {
                excluded.add(exclusion(assetId, "사진 검사 결과를 불러오지 못해 제외했어요."));
                continue;
            }
            if (analysis == null || !"completed".equals(analysis.get("status"))) {
                excluded.add(exclusion(assetId, "최근 사진 검사가 완료 상태가 아니어서 제외했어요."));
                continue;
            }
            if (Boolean.TRUE.equals(analysis.get("mock"))) {
                excluded.add(exclusion(assetId, "모의 검사 결과라 누적 점수에 반영하지 않았어요."));
                continue;
            }
            photos.add(new PhotoEvidence(asset, inspection, analysis, sha256(assetId),
                    asset.getCaptureLatitude(), asset.getCaptureLongitude()));
        }

        List<GroupEvidence> groups = group(photos, place);
        CumulativeScoreCalculator.Score calculation = CumulativeScoreCalculator.calculate(groups.stream()
                .map(group -> new CumulativeScoreCalculator.GroupScore(group.specificity(), group.visitDays(),
                        group.placeDistanceWeight())).toList());
        double repeat = calculation.repeatComponent();
        double combination = calculation.combinationComponent();
        Integer score = calculation.score();
        String level = score == null ? "not_assessed" : score >= 70 ? "high" : score >= 40 ? "caution" : "low";

        Map<String, Object> snapshot = new LinkedHashMap<>();
        UUID runId = UUID.randomUUID();
        snapshot.put("runId", runId);
        snapshot.put("placeId", placeId);
        snapshot.put("algorithmVersion", VERSION);
        snapshot.put("score", score);
        snapshot.put("scoreMeaning", "relative_score_not_probability");
        snapshot.put("mock", false);
        snapshot.put("scoreStatus", score == null ? "not_assessed" : "calculated");
        snapshot.put("scoreNotice", score == null
                ? "단독 검사에서 평가된 위치 단서가 없어 점수를 계산하지 않았어요."
                : "초기 규칙 기반 상대 점수예요. 확률이나 주소 식별 결과가 아니며 규칙을 개선할 수 있어요.");
        snapshot.put("riskLevel", level);
        snapshot.put("repeatComponent", round(repeat));
        snapshot.put("combinationComponent", round(combination));
        snapshot.put("groups", groups.stream().map(GroupEvidence::toMap).toList());
        snapshot.put("excluded", excluded);
        snapshot.put("createdAt", Instant.now().toString());

        runs.save(new CumulativeRun(runId, placeId, VERSION, JSON.writeValueAsString(snapshot)));
        return snapshot;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(UUID installationId, UUID runId) {
        CumulativeRun run = runs.findById(runId)
                .orElseThrow(() -> new ApiFailure(404, "CUMULATIVE_RUN_NOT_FOUND", "누적 분석 결과를 찾을 수 없습니다."));
        requirePlace(installationId, run.getPlaceId());
        return decode(run.getResultJson());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRuns(UUID installationId, UUID placeId) {
        requirePlace(installationId, placeId);
        return runs.findAllByPlaceIdOrderByCreatedAtDesc(placeId).stream()
                .map(item -> decode(item.getResultJson())).toList();
    }

    private List<GroupEvidence> group(List<PhotoEvidence> input, SensitivePlace place) {
        List<GroupEvidence> groups = new ArrayList<>();
        Map<UUID, PhotoEvidence> byId = input.stream().collect(java.util.stream.Collectors.toMap(
                photo -> photo.asset().getId(), photo -> photo));
        List<CumulativeSpatialGrouper.Point> points = input.stream().map(photo ->
                new CumulativeSpatialGrouper.Point(photo.asset().getId(), photo.latitude(), photo.longitude())).toList();
        for (CumulativeSpatialGrouper.Group group : CumulativeSpatialGrouper.group(points)) {
            PhotoEvidence center = byId.get(group.centerId());
            List<PhotoEvidence> members = group.memberIds().stream().map(byId::get).toList();
            groups.add(buildGroup(center, members, place));
        }
        return groups;
    }

    private GroupEvidence buildGroup(PhotoEvidence center, List<PhotoEvidence> members, SensitivePlace place) {
        List<CueEvidence> cues = new ArrayList<>();
        for (PhotoEvidence photo : members) {
            Object raw = photo.analysis().get("riskFactors");
            if (!(raw instanceof List<?> factors)) continue;
            for (Object item : factors) {
                if (!(item instanceof Map<?, ?> rawCue)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> cue = (Map<String, Object>) rawCue;
                double specificity = specificity(cue);
                if (specificity <= 0) continue;
                cues.add(new CueEvidence(photo, String.valueOf(cue.getOrDefault("id", "")),
                        readableCue(cue), specificity));
            }
        }
        double specificity = cues.stream().mapToDouble(CueEvidence::specificity).max().orElse(0);
        CueEvidence strongest = cues.stream().max(Comparator.comparingDouble(CueEvidence::specificity)
                .thenComparing(CueEvidence::title, Comparator.reverseOrder())).orElse(null);
        List<PhotoEvidence> uniquePhotos = members.stream().collect(java.util.stream.Collectors.toMap(
                PhotoEvidence::contentHash, photo -> photo, (first, ignored) -> first, LinkedHashMap::new)).values().stream().toList();
        long visitDays = uniquePhotos.stream().map(PhotoEvidence::visitDate).distinct().count();
        double distanceToPlace = CumulativeSpatialGrouper.distance(center.latitude(), center.longitude(), place.getLatitude(), place.getLongitude());
        double weight = distanceWeight(distanceToPlace);
        double repeatContribution = CumulativeScoreCalculator.repeatContribution(specificity, (int) visitDays, weight);
        String groupName = strongest == null ? "장소 묶음 · " + members.size() + "장"
                : strongest.title() + " · " + members.size() + "장";
        return new GroupEvidence(UUID.randomUUID(), groupName, members, uniquePhotos, cues, specificity,
                (int) visitDays, distanceBand(distanceToPlace), weight, repeatContribution);
    }

    private double specificity(Map<String, Object> cue) {
        Object human = cue.get("humanRisk");
        Object model = cue.get("modelRisk");
        String risk = human != null && !"null".equalsIgnoreCase(String.valueOf(human))
                ? String.valueOf(human) : String.valueOf(model);
        return switch (risk.toLowerCase()) {
            case "high", "높음", "지점 하나", "specific" -> 1.0;
            case "caution", "medium", "주의", "몇 곳", "neighborhood" -> 0.6;
            case "low", "낮음", "city", "district" -> 0.2;
            default -> 0.0;
        };
    }

    private String readableCue(Map<String, Object> cue) {
        for (String key : List.of("title", "name", "text", "type")) {
            Object value = cue.get(key);
            if (value instanceof String text && !text.isBlank() && !"null".equalsIgnoreCase(text)) return text;
        }
        return "평가된 위치 단서";
    }

    private double distanceWeight(double meters) {
        if (meters <= 500) return 1.0;
        if (meters <= 1_000) return 0.8;
        if (meters <= 3_000) return 0.5;
        return 0.0;
    }

    private String distanceBand(double meters) {
        if (meters <= 500) return "within_500m";
        if (meters <= 1_000) return "within_1km";
        if (meters <= 3_000) return "within_3km";
        return "over_3km";
    }

    private String sha256(UUID assetId) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(assetService.loadForAnalysis(assetId).bytes())); }
        catch (Exception failure) { return assetId.toString(); }
    }

    private SensitivePlace requirePlace(UUID installationId, UUID placeId) {
        return places.findByIdAndInstallationId(placeId, installationId)
                .orElseThrow(() -> new ApiFailure(404, "PLACE_NOT_FOUND", "등록한 장소를 찾을 수 없습니다."));
    }

    private Map<String, Object> placeView(SensitivePlace place) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("placeId", place.getId());
        view.put("name", place.getName());
        view.put("category", place.getCategory());
        view.put("radiusMeters", place.getRadiusMeters());
        view.put("createdAt", place.getCreatedAt());
        // Coordinates and address go back only to an authenticated owner (login mode); an unverified installation
        // header must never be enough to read where someone lives.
        if (ownership.loginMode()) {
            view.put("latitude", place.getLatitude());
            view.put("longitude", place.getLongitude());
            view.put("address", place.getAddress());
        }
        return view;
    }

    private static String address(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    private Map<String, Object> assetView(ImageAsset asset) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("assetId", asset.getId());
        view.put("contentType", asset.getContentType());
        view.put("sizeBytes", asset.getSizeBytes());
        view.put("locationStatus", asset.getCaptureLocationSource());
        view.put("capturedAt", asset.getCapturedAt());
        view.put("labels", asset.getLabels());
        view.put("assetRole", asset.getAssetRole());
        view.put("mock", asset.isMock());
        view.put("createdAt", asset.getCreatedAt());
        return view;
    }

    private Map<String, Object> exclusion(UUID assetId, String reason) {
        return Map.of("assetId", assetId, "reason", reason);
    }

    private void validateCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180)
            throw new ApiFailure(400, "INVALID_PLACE_LOCATION", "장소 위치를 확인해 주세요.");
    }

    private void validateRadius(Integer radiusMeters) {
        if (radiusMeters == null || !List.of(100, 300, 500, 1_000).contains(radiusMeters))
            throw new ApiFailure(400, "INVALID_PLACE_RADIUS", "보호 반경은 100m, 300m, 500m, 1km 중에서 선택해 주세요.");
    }

    private double round(double value) { return Math.round(value * 10_000.0) / 10_000.0; }

    private Map<String, Object> decode(String json) {
        return JSON.readValue(json, new TypeReference<>() {});
    }

    private record PhotoEvidence(ImageAsset asset, Inspection inspection, Map<String, Object> analysis,
                                 String contentHash, double latitude, double longitude) {
        private LocalDate visitDate() {
            return asset.getCapturedAt() == null ? asset.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate()
                    : asset.getCapturedAt().toLocalDate();
        }
    }

    private record CueEvidence(PhotoEvidence photo, String id, String title, double specificity) {}

    private record GroupEvidence(UUID groupId, String name, List<PhotoEvidence> photos,
                                 List<PhotoEvidence> uniquePhotos, List<CueEvidence> cues,
                                 double specificity, int visitDays, String distanceBand,
                                 double placeDistanceWeight, double repeatContribution) {
        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("groupId", groupId);
            result.put("name", name);
            result.put("assetIds", photos.stream().map(photo -> photo.asset().getId()).toList());
            result.put("inspectionIds", photos.stream().map(photo -> photo.inspection().getInspectionId()).distinct().toList());
            result.put("photoCount", photos.size());
            result.put("independentPhotoCount", uniquePhotos.size());
            result.put("visitDays", visitDays);
            result.put("locationGroupRadiusMeters", (int) CumulativeSpatialGrouper.RADIUS_METERS);
            result.put("placeDistanceBand", distanceBand);
            result.put("specificity", specificity);
            result.put("repeatContribution", Math.round(repeatContribution * 10_000.0) / 10_000.0);
            result.put("evidence", cues.stream().map(cue -> Map.of(
                    "assetId", cue.photo().asset().getId(),
                    "inspectionId", cue.photo().inspection().getInspectionId(),
                    "cueId", cue.id(), "title", cue.title(), "specificity", cue.specificity())).toList());
            return result;
        }
    }
}
