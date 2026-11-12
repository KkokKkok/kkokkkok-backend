package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.service.GeocodingService;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geocoding")
public class GeocodingController {
    private final GeocodingService service;
    public GeocodingController(GeocodingService service) { this.service = service; }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("query") String query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(query));
    }

    @GetMapping("/reverse")
    public ResponseEntity<Map<String, Object>> reverse(@RequestParam("latitude") double latitude,
                                                       @RequestParam("longitude") double longitude) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reverse(latitude, longitude));
    }
}
