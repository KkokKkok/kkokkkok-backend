package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.SensitivePlace;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensitivePlaceRepository extends JpaRepository<SensitivePlace, UUID> {
    List<SensitivePlace> findAllByInstallationIdOrderByCreatedAtAsc(UUID installationId);
    java.util.Optional<SensitivePlace> findByIdAndInstallationId(UUID id, UUID installationId);
}
