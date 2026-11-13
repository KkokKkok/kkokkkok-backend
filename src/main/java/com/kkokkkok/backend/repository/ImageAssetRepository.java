package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.ImageAsset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, UUID> {
    java.util.List<ImageAsset> findAllByOwnerInstallationIdOrderByCreatedAtDesc(UUID ownerInstallationId);
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update ImageAsset a set a.ownerInstallationId = :owner where a.ownerInstallationId = :installation")
    int reassignOwner(@org.springframework.data.repository.query.Param("installation") UUID installation,
                      @org.springframework.data.repository.query.Param("owner") UUID owner);
}
