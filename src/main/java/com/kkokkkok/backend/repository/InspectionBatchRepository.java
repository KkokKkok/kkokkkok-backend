package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.InspectionBatch;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionBatchRepository extends JpaRepository<InspectionBatch, UUID> {}
