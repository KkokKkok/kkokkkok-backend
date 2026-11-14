package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** codeChallenge: base64url(SHA-256(code verifier)) kept by the app. installationId: optional app installation UUID. */
public record NaverLoginStartRequest(@NotBlank @Size(max = 64) String codeChallenge, UUID installationId) {}
