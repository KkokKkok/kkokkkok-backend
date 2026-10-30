package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.ImageAsset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, UUID> {}
