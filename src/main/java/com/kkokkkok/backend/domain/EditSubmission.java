package com.kkokkkok.backend.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Provider-neutral edit input selected from a stored single-analysis result. */
public record EditSubmission(UUID sourceAssetId, byte[] image, String contentType,
                             List<Map<String, Object>> selectedCues, String preservePrompt) {}
