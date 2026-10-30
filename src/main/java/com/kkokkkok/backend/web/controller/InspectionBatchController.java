package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.InspectionBatchService;
import com.kkokkkok.backend.web.dto.InspectionBatchRequest;
import com.kkokkkok.backend.web.dto.InspectionBatchResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inspection-batches")
public class InspectionBatchController {
    private final InspectionBatchService service;
    public InspectionBatchController(InspectionBatchService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<InspectionBatchResponse> create(@Valid @RequestBody InspectionBatchRequest request,
            @RequestHeader("Idempotency-Key") UUID batchId) {
        var result = service.create(request, batchId);
        return ResponseEntity.accepted().location(URI.create("/api/inspection-batches/" + result.batchId())).body(result);
    }
    @GetMapping("/{batchId}")
    public InspectionBatchResponse get(@PathVariable UUID batchId) { return service.get(batchId); }
}
