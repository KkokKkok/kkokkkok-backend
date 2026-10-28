package com.kkokkkok.backend.web.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import java.util.Map;
import java.util.UUID;

/** Keeps the existing flat JSON contract, including Node's evolving result fields. */
public record InspectionResponse(UUID inspectionId, String status,
        @JsonAnyGetter Map<String,Object> details) {}
