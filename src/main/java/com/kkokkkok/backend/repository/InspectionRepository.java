package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.Inspection;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionRepository extends JpaRepository<Inspection,UUID> {
    Optional<Inspection> findByInspectionId(UUID inspectionId);
    List<Inspection> findAllByAssetIdOrderByCreatedAtDesc(UUID assetId);
}
