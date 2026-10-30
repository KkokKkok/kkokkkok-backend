package com.kkokkkok.backend.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record InspectionBatchRequest(@NotEmpty @Size(max = 20) List<@Valid Item> items) {
    public record Item(@NotNull UUID assetId, @Size(max = 120) String locationFallback) {}
}
