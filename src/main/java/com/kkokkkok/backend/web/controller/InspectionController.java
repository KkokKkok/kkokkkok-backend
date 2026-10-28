package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.InspectionService;
import com.kkokkkok.backend.web.dto.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inspections")
public class InspectionController {
    private final InspectionService service;
    public InspectionController(InspectionService service) {this.service=service;}
    @PostMapping(consumes="multipart/form-data")
    public ResponseEntity<InspectionResponse> create(@Valid @ModelAttribute InspectionRequest request,
            @RequestHeader("Idempotency-Key") UUID requestId) {
        var result=service.create(request,requestId);
        return ResponseEntity.accepted().location(URI.create("/api/inspections/"+result.inspectionId())).body(result);
    }
    @GetMapping("/{id}")
    public InspectionResponse get(@PathVariable UUID id) {return service.get(id);}
}
