package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/api/installations")
@ConditionalOnProperty(name = "app.pilot.enabled", havingValue = "true")
public class PilotInstallationController {
    private final PilotSessionService sessions;
    private final PilotOwnership ownership;
    public PilotInstallationController(PilotSessionService sessions, PilotOwnership ownership) { this.sessions = sessions; this.ownership = ownership; }
    @PostMapping("/enroll")
    public ResponseEntity<PilotSessionService.Enrollment> enroll(@Valid @RequestBody EnrollRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(sessions.enroll(request.inviteCode()));
    }
    @GetMapping("/current")
    public Map<String, UUID> current() { return Map.of("installationId", ownership.current()); }
    public record EnrollRequest(@NotBlank @Size(max = 64) String inviteCode) {
        @Override public String toString() { return "EnrollRequest[redacted]"; }
    }
}
