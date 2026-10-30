package com.kkokkkok.backend.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/** Analysis input resolved from a stored asset. The bytes live only for the outgoing request. */
public record InspectionSubmission(UUID assetId, byte[] photo, String contentType,
        Double latitude, Double longitude, LocalDateTime capturedAt, String fingerprint) {}
