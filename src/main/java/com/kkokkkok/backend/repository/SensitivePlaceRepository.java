package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.SensitivePlace;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensitivePlaceRepository extends JpaRepository<SensitivePlace, UUID> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update SensitivePlace p set p.installationId = :owner where p.installationId = :installation")
    int reassignOwner(@org.springframework.data.repository.query.Param("installation") UUID installation,
                      @org.springframework.data.repository.query.Param("owner") UUID owner);
    List<SensitivePlace> findAllByInstallationIdOrderByCreatedAtAsc(UUID installationId);
    java.util.Optional<SensitivePlace> findByIdAndInstallationId(UUID id, UUID installationId);
}
