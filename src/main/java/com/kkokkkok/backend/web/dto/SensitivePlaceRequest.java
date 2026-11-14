package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code address} is the label the user picked on the map; optional and stored only for the owner's own view. */
public record SensitivePlaceRequest(@NotBlank @Size(max = 80) String name,
                                    @NotBlank @Size(max = 16) String category,
                                    double latitude, double longitude, Integer radiusMeters,
                                    @Size(max = 200) String address) {
    public SensitivePlaceRequest(String name, String category, double latitude, double longitude) {
        this(name, category, latitude, longitude, 300, null);
    }

    public SensitivePlaceRequest(String name, String category, double latitude, double longitude, Integer radiusMeters) {
        this(name, category, latitude, longitude, radiusMeters, null);
    }
}
