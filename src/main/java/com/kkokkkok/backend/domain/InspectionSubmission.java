package com.kkokkkok.backend.domain;

/** Validated, normalized input. The bytes live only for the outgoing request. */
public record InspectionSubmission(byte[] photo, String contentType, String address,
        String placeId, String locationRole, boolean compareHistory, String fingerprint) {}
