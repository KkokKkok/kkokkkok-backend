package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.PilotInvite;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PilotInviteRepository extends JpaRepository<PilotInvite, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PilotInvite i where i.codeHash = :hash")
    Optional<PilotInvite> lockByHash(@Param("hash") String hash);
}
