package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AssetLabelsRequest(@NotNull @Size(max = 10) List<@Size(max = 40) String> labels) {}
