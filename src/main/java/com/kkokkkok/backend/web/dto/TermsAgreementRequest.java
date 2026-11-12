package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Figma 약관 항목: 서비스 이용약관·개인정보 수집·이용·위치정보 이용(필수), 알림 수신(선택). */
public record TermsAgreementRequest(@NotBlank @Size(max = 40) String termsVersion,
                                    @NotNull Boolean serviceTerms, @NotNull Boolean privacy,
                                    @NotNull Boolean location, @NotNull Boolean marketing) {}
