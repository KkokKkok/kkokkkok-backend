package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.ProfileService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final ProfileService service;
    private final com.kkokkkok.backend.service.DataDeletionService deletion;
    public ProfileController(ProfileService service, com.kkokkkok.backend.service.DataDeletionService deletion) { this.service=service; this.deletion=deletion; }
    @DeleteMapping("/data")
    public org.springframework.http.ResponseEntity<Void> delete(@RequestHeader("X-Installation-Id") UUID installationId) {
        deletion.delete(installationId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }
    @GetMapping
    public Map<String,Object> overview(@RequestHeader("X-Installation-Id") UUID installationId) {
        return service.overview(installationId);
    }
}
