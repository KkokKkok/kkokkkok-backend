package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CumulativeRunRequest(@NotNull UUID placeId,
                                   @NotEmpty @Size(max = 100) List<@NotNull UUID> assetIds) {}
