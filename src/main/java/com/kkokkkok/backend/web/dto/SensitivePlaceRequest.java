package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SensitivePlaceRequest(@NotBlank @Size(max = 80) String name,
                                    @NotBlank @Size(max = 16) String category,
                                    double latitude, double longitude, Integer radiusMeters) {
    public SensitivePlaceRequest(String name, String category, double latitude, double longitude) {
        this(name, category, latitude, longitude, 300);
    }
}
