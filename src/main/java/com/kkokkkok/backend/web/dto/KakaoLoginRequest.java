package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** accessToken: Kakao user access token from the Android SDK. installationId: optional app installation UUID. */
public record KakaoLoginRequest(@NotBlank @Size(max = 4096) String accessToken, UUID installationId) {
    @Override public String toString() { return "KakaoLoginRequest[redacted]"; }
}
