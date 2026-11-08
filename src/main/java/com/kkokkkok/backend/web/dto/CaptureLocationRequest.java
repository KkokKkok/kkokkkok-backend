package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CaptureLocationRequest(double latitude, double longitude,
                                     @NotBlank String source) {}
