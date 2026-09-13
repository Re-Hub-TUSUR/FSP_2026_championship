package com.example.balloon.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Сессия входа. В базе лежит только SHA-256 токена — сам токен знает лишь клиент, поэтому
 * содержимое таблицы не позволяет войти в аккаунт. Сессии переживают перезапуск сервера.
 */
@Entity
@Table(name = "sessions", indexes = {
    @Index(name = "idx_sessions_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_sessions_account", columnList = "account_id"),
    @Index(name = "idx_sessions_expires", columnList = "expires_at")
})
public class SessionEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private String id;

  @Column(name = "account_id", nullable = false, length = 36)
  private String accountId;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_used_at", nullable = false)
  private Instant lastUsedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected SessionEntity() {}

  public SessionEntity(String accountId, String tokenHash, Instant createdAt, Instant expiresAt) {
    this.accountId = accountId;
    this.tokenHash = tokenHash;
    this.createdAt = createdAt;
    this.lastUsedAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public String getId() { return id; }
  public String getAccountId() { return accountId; }
  public String getTokenHash() { return tokenHash; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getLastUsedAt() { return lastUsedAt; }
  public Instant getExpiresAt() { return expiresAt; }
}
