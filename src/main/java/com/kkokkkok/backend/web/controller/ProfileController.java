package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.ProfileService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final ProfileService service;
    public ProfileController(ProfileService service) { this.service=service; }
    @GetMapping
    public Map<String,Object> overview(@RequestHeader("X-Installation-Id") UUID installationId) {
        return service.overview(installationId);
    }
}
