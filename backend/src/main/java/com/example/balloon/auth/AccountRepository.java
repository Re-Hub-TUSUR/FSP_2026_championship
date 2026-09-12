package com.example.balloon.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;
public interface AccountRepository extends JpaRepository<AccountEntity,String> {
    Optional<AccountEntity> findByUsernameIgnoreCase(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where lower(a.username) = lower(:username)")
    Optional<AccountEntity> findByUsernameIgnoreCaseForUpdate(@Param("username") String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findByIdForUpdate(@Param("id") String id);

    boolean existsByUsernameIgnoreCase(String username);
    List<AccountEntity> findAllByOrderByPointsDesc();
}
