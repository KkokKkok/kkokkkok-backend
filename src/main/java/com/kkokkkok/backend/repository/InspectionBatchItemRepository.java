package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.InspectionBatchItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionBatchItemRepository extends JpaRepository<InspectionBatchItem, UUID> {
    List<InspectionBatchItem> findAllByBatchIdOrderByPosition(UUID batchId);
}
