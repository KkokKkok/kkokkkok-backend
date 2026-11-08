package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.CumulativeAnalysisService;
import com.kkokkkok.backend.web.dto.CumulativeRunRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cumulative-runs")
public class CumulativeAnalysisController {
    private final CumulativeAnalysisService service;
    public CumulativeAnalysisController(CumulativeAnalysisService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> run(
            @RequestHeader("X-Installation-Id") UUID installationId,
            @Valid @RequestBody CumulativeRunRequest request) {
        Map<String, Object> result = service.run(installationId, request.placeId(), request.assetIds());
        return ResponseEntity.created(URI.create("/api/cumulative-runs/" + result.get("runId"))).body(result);
    }

    @GetMapping("/{runId}")
    public Map<String, Object> get(@RequestHeader("X-Installation-Id") UUID installationId,
                                   @PathVariable UUID runId) {
        return service.getRun(installationId, runId);
    }

    @GetMapping("/place/{placeId}")
    public List<Map<String, Object>> listForPlace(@RequestHeader("X-Installation-Id") UUID installationId,
                                                  @PathVariable UUID placeId) {
        return service.listRuns(installationId, placeId);
    }
}
