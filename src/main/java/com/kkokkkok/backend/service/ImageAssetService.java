package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.ImageAsset;
import com.kkokkkok.backend.repository.ImageAssetRepository;
import com.kkokkkok.backend.web.dto.AssetResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageAssetService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final ImageAssetRepository repository;
    private final LocalAssetStorage storage;
    private final ImageMetadataExtractor metadataExtractor;

    public ImageAssetService(ImageAssetRepository repository, LocalAssetStorage storage,
                             ImageMetadataExtractor metadataExtractor) {
        this.repository = repository;
        this.storage = storage;
        this.metadataExtractor = metadataExtractor;
    }

    @Transactional
    public AssetResponse upload(MultipartFile file, List<String> labels) {
        if (file == null || file.isEmpty()) throw new ApiFailure(400, "INVALID_PHOTO", "사진을 선택해 주세요.");
        if (file.getSize() > MAX_BYTES) throw new ApiFailure(413, "PHOTO_TOO_LARGE", "사진은 10MB 이하여야 합니다.");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (Exception e) { throw new ApiFailure(400, "INVALID_PHOTO", "사진을 읽을 수 없습니다."); }
        String contentType = imageType(bytes);
        List<String> safeLabels = normalizeLabels(labels);
        var metadata = metadataExtractor.extract(bytes);
        UUID id = UUID.randomUUID();
        String key = storage.save(id, bytes, contentType);
        try {
            return AssetResponse.from(repository.save(new ImageAsset(id, contentType, bytes.length, key,
                    metadata.latitude(), metadata.longitude(), metadata.capturedAt(), metadata.locationStatus(), safeLabels)));
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<AssetResponse> list() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream().map(AssetResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AssetResponse get(UUID id) { return AssetResponse.from(find(id)); }

    @Transactional(readOnly = true)
    public StoredAsset readContent(UUID id) {
        ImageAsset asset = find(id);
        return storedAsset(asset);
    }

    @Transactional(readOnly = true)
    public StoredAsset loadForAnalysis(UUID id) { return storedAsset(find(id)); }

    @Transactional
    public AssetResponse replaceLabels(UUID id, List<String> labels) {
        ImageAsset asset = find(id);
        asset.replaceLabels(normalizeLabels(labels));
        return AssetResponse.from(asset);
    }

    @Transactional
    public void delete(UUID id) {
        ImageAsset asset = find(id);
        storage.delete(asset.getStorageKey());
        repository.delete(asset);
    }

    private ImageAsset find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ApiFailure(404, "ASSET_NOT_FOUND", "사진을 찾을 수 없습니다."));
    }

    private StoredAsset storedAsset(ImageAsset asset) {
        return new StoredAsset(asset.getId(), asset.getContentType(), storage.read(asset.getStorageKey()),
                asset.getLatitude(), asset.getLongitude(), asset.getCapturedAt());
    }

    private List<String> normalizeLabels(List<String> labels) {
        if (labels == null) return List.of();
        if (labels.size() > 10) throw new ApiFailure(400, "INVALID_LABELS", "사진 라벨은 10개까지 지정할 수 있습니다.");
        var normalized = labels.stream().map(value -> value == null ? "" : value.trim()).toList();
        if (normalized.stream().anyMatch(value -> value.isBlank() || value.length() > 40))
            throw new ApiFailure(400, "INVALID_LABELS", "라벨은 비어 있을 수 없고 40자 이하여야 합니다.");
        if (normalized.stream().distinct().count() != normalized.size())
            throw new ApiFailure(400, "INVALID_LABELS", "같은 라벨을 중복 지정할 수 없습니다.");
        return normalized;
    }

    private String imageType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "image/jpeg";
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        throw new ApiFailure(415, "UNSUPPORTED_PHOTO", "JPEG, PNG, WebP 사진만 지원합니다.");
    }

    public record StoredAsset(UUID assetId, String contentType, byte[] bytes, Double latitude,
                              Double longitude, java.time.LocalDateTime capturedAt) {}
}
