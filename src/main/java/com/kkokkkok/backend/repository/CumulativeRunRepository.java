package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.CumulativeRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CumulativeRunRepository extends JpaRepository<CumulativeRun, UUID> {
    List<CumulativeRun> findAllByPlaceIdOrderByCreatedAtDesc(UUID placeId);
}
