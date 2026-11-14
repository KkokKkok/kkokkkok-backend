package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** code: one-time code from the app link. codeVerifier: secret the app generated before starting the login. */
public record NaverLoginExchangeRequest(@NotBlank @Size(max = 128) String code, @NotBlank @Size(max = 128) String codeVerifier) {
    @Override public String toString() { return "NaverLoginExchangeRequest[redacted]"; }
}
