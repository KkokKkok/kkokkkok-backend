package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.PilotInstallation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PilotInstallationRepository extends JpaRepository<PilotInstallation, UUID> {
    Optional<PilotInstallation> findByTokenHash(String tokenHash);
}
