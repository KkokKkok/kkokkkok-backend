package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.PlaceAssetLink;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceAssetLinkRepository extends JpaRepository<PlaceAssetLink, UUID> {
    List<PlaceAssetLink> findAllByPlaceIdOrderByCreatedAtDesc(UUID placeId);
    Optional<PlaceAssetLink> findByPlaceIdAndAssetId(UUID placeId, UUID assetId);
    @Modifying @Transactional
    void deleteByPlaceIdAndAssetId(UUID placeId, UUID assetId);
    @Modifying @Transactional
    void deleteAllByPlaceId(UUID placeId);
}
