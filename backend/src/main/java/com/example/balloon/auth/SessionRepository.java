package com.example.balloon.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {
    Optional<SessionEntity> findByTokenHash(String tokenHash);

    /** Массовое удаление не падает, если параллельный запрос уже удалил ту же сессию. */
    @Transactional
    @Modifying
    @Query("delete from SessionEntity s where s.tokenHash = :tokenHash")
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);

    @Transactional
    @Modifying
    @Query("delete from SessionEntity s where s.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") String accountId);

    @Transactional
    @Modifying
    @Query("delete from SessionEntity s where s.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    /** Продлевает скользящий срок одним UPDATE, без загрузки записи. */
    @Transactional
    @Modifying
    @Query("update SessionEntity s set s.lastUsedAt = :usedAt, s.expiresAt = :expiresAt where s.id = :id")
    int touch(@Param("id") String id, @Param("usedAt") Instant usedAt, @Param("expiresAt") Instant expiresAt);
}
