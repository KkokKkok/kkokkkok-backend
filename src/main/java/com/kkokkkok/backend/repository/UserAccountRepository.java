package com.kkokkkok.backend.repository;

import com.kkokkkok.backend.domain.UserAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {}
