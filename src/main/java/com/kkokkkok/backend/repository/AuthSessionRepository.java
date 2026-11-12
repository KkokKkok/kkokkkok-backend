package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.AuthSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    Optional<AuthSession> findByAccessTokenHash(String hash);
    Optional<AuthSession> findByRefreshTokenHash(String hash);
    Optional<AuthSession> findByPreviousRefreshTokenHash(String hash);
}
