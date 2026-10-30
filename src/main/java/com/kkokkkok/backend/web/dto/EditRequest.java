package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record EditRequest(@NotNull UUID inspectionId,
                          @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 100) String> cueIds,
                          @Size(max = 1000) String preservePrompt) {}
