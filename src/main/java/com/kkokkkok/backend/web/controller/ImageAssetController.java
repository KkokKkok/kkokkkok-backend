package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.ImageAssetService;
import com.kkokkkok.backend.web.dto.AssetLabelsRequest;
import com.kkokkkok.backend.web.dto.AssetResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assets")
public class ImageAssetController {
    private final ImageAssetService service;
    public ImageAssetController(ImageAssetService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AssetResponse> upload(@RequestPart("photo") MultipartFile photo,
                                                @RequestParam(required = false) List<String> labels) {
        AssetResponse result = service.upload(photo, labels);
        return ResponseEntity.created(URI.create("/api/assets/" + result.assetId())).body(result);
    }

    @GetMapping
    public List<AssetResponse> list() { return service.list(); }

    @GetMapping("/{id}")
    public AssetResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        var asset = service.readContent(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(asset.bytes().length))
                .body(asset.bytes());
    }

    @PutMapping("/{id}/labels")
    public AssetResponse labels(@PathVariable UUID id, @Valid @RequestBody AssetLabelsRequest request) {
        return service.replaceLabels(id, request.labels());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
