package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.EditRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EditRunRepository extends JpaRepository<EditRun, UUID> {}
