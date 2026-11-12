package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.TermsAgreement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsAgreementRepository extends JpaRepository<TermsAgreement, UUID> {
    Optional<TermsAgreement> findFirstByUserIdOrderByAgreedAtDesc(UUID userId);
}
