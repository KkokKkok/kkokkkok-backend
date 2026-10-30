package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Starts one analysis for an image already stored in the service gallery. */
public record InspectionRequest(@NotNull UUID assetId, @Size(max = 120) String locationFallback) {
    public InspectionRequest(UUID assetId) { this(assetId, null); }
}
