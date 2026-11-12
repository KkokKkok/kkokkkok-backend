package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.TermsAgreement;
import com.kkokkkok.backend.repository.TermsAgreementRepository;
import com.kkokkkok.backend.web.dto.TermsAgreementRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores consent server-side. A user must agree to the current terms version before using data APIs. */
@Service
public class TermsService {
    private final TermsAgreementRepository agreements;
    private final String currentVersion;

    public TermsService(TermsAgreementRepository agreements, @Value("${auth.terms-version:2026-11-13}") String currentVersion) {
        if (currentVersion.isBlank() || currentVersion.length() > 40) throw new IllegalArgumentException("auth.terms-version must be 1..40 chars");
        this.agreements = agreements; this.currentVersion = currentVersion;
    }

    public String currentVersion() { return currentVersion; }

    @Transactional(readOnly = true)
    public boolean accepted(UUID userId) {
        return agreements.findFirstByUserIdOrderByAgreedAtDesc(userId)
                .filter(a -> currentVersion.equals(a.getTermsVersion()) && a.requiredAccepted()).isPresent();
    }

    @Transactional
    public Map<String, Object> agree(UUID userId, TermsAgreementRequest request) {
        if (!currentVersion.equals(request.termsVersion()))
            throw new ApiFailure(409, "TERMS_VERSION_MISMATCH", "약관이 변경되었어요. 새 약관을 확인해 주세요.");
        if (!request.serviceTerms() || !request.privacy() || !request.location())
            throw new ApiFailure(400, "REQUIRED_TERMS_NOT_ACCEPTED", "필수 약관에 모두 동의해 주세요.");
        var saved = agreements.saveAndFlush(new TermsAgreement(UUID.randomUUID(), userId, currentVersion,
                true, true, true, request.marketing(), Instant.now()));
        return status(saved);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(UUID userId) {
        return agreements.findFirstByUserIdOrderByAgreedAtDesc(userId).map(this::status).orElseGet(() -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("currentVersion", currentVersion);
            map.put("accepted", false);
            map.put("agreedVersion", null);
            map.put("marketing", false);
            map.put("agreedAt", null);
            return map;
        });
    }

    private Map<String, Object> status(TermsAgreement agreement) {
        var map = new LinkedHashMap<String, Object>();
        map.put("currentVersion", currentVersion);
        map.put("accepted", currentVersion.equals(agreement.getTermsVersion()) && agreement.requiredAccepted());
        map.put("agreedVersion", agreement.getTermsVersion());
        map.put("marketing", agreement.isMarketing());
        map.put("agreedAt", agreement.getAgreedAt());
        return map;
    }
}
