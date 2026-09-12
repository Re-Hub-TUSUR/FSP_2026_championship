package com.example.balloon.daily;

import com.example.balloon.auth.AccountEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Прогресс одного игрока за один календарный день. Отдельная запись на дату делает
 * невозможным перенос фрагментов между днями и сохраняет уже открытый билет.
 */
@Entity
@Table(name = "daily_bonuses",
    uniqueConstraints = @UniqueConstraint(name = "uk_daily_bonus_account_date", columnNames = {"account_id", "bonus_date"}),
    indexes = @Index(name = "idx_daily_bonus_account_date", columnList = "account_id,bonus_date"))
public class DailyBonusEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "account_id", nullable = false)
  private AccountEntity account;

  @Column(name = "bonus_date", nullable = false)
  private LocalDate bonusDate;

  @Column(nullable = false, columnDefinition = "integer default 0 not null")
  private int puzzleMask;

  /** Билет выдан после сбора всех фрагментов, даже если его уже стёрли. */
  @Column(nullable = false)
  private boolean ticketIssued;

  @Column(nullable = false)
  private boolean claimed;

  /** null, пока стирание билета не завершено на сервере. */
  private Long reward;

  /** Баланс непосредственно после начисления билета (для повторяемого API-ответа). */
  private Long balanceAfter;

  @Column(length = 128)
  private String claimIdempotencyKey;

  private Instant claimedAt;

  @Version
  private long version;

  protected DailyBonusEntity() {}

  public DailyBonusEntity(AccountEntity account, LocalDate bonusDate) {
    this.account = account;
    this.bonusDate = bonusDate;
  }

  public String getId() { return id; }
  public LocalDate getBonusDate() { return bonusDate; }
  public int getPuzzleMask() { return puzzleMask; }
  public boolean isTicketIssued() { return ticketIssued; }
  public boolean isClaimed() { return claimed; }
  public Long getReward() { return reward; }
  public Long getBalanceAfter() { return balanceAfter; }
  public String getClaimIdempotencyKey() { return claimIdempotencyKey; }
  public Instant getClaimedAt() { return claimedAt; }

  public void setPuzzleMask(int puzzleMask) { this.puzzleMask = puzzleMask; }
  public void issueTicket() { this.ticketIssued = true; }

  public void claim(long reward, String idempotencyKey, long balanceAfter) {
    if (claimed) return;
    this.claimed = true;
    this.reward = reward;
    this.balanceAfter = balanceAfter;
    this.claimIdempotencyKey = idempotencyKey;
    this.claimedAt = Instant.now();
  }
}
