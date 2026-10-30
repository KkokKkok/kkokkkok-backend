package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.InspectionService;
import com.kkokkkok.backend.web.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inspections")
public class InspectionController {
    private final InspectionService service;
    public InspectionController(InspectionService service) {this.service=service;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InspectionResponse> create(@Valid @RequestBody InspectionRequest request,
            @RequestHeader("Idempotency-Key") UUID requestId) {
        var result=service.create(request,requestId);
        return ResponseEntity.accepted().location(URI.create("/api/inspections/"+result.inspectionId())).body(result);
    }
    @GetMapping("/{id}")
    public InspectionResponse get(@PathVariable UUID id) {return service.get(id);}

    @GetMapping(params = "assetId")
    public List<InspectionHistoryItem> history(@RequestParam UUID assetId) { return service.history(assetId); }
}
