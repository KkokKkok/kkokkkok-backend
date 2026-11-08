package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PlaceAssetRequest(@NotNull UUID assetId) {}
