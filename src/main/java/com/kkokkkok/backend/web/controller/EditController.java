package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.EditService;
import com.kkokkkok.backend.web.dto.EditRequest;
import com.kkokkkok.backend.web.dto.EditResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/edits")
public class EditController {
    private final EditService service;
    public EditController(EditService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<EditResponse> create(@Valid @RequestBody EditRequest request,
                                               @RequestHeader("Idempotency-Key") UUID editId) {
        EditResponse result = service.create(request, editId);
        return ResponseEntity.created(URI.create("/api/edits/" + result.editId())).body(result);
    }

    @GetMapping("/{editId}")
    public EditResponse get(@PathVariable UUID editId) { return service.get(editId); }
}
