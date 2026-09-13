package com.example.balloon.tournament;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Турнир рейтинга. Срок и факт выплаты призов хранятся в базе: перезапуск сервера
 * не начинает турнир заново и не начисляет призы второй раз.
 */
@Entity
@Table(name = "tournaments")
public class TournamentEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private String id;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "ends_at", nullable = false)
  private Instant endsAt;

  @Column(name = "prizes_paid", nullable = false)
  private boolean prizesPaid;

  @Column(name = "prizes_paid_at")
  private Instant prizesPaidAt;

  protected TournamentEntity() {}

  public TournamentEntity(Instant startedAt, Instant endsAt) {
    this.startedAt = startedAt;
    this.endsAt = endsAt;
  }

  public String getId() { return id; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getEndsAt() { return endsAt; }
  public boolean isPrizesPaid() { return prizesPaid; }
  public Instant getPrizesPaidAt() { return prizesPaidAt; }

  public void markPrizesPaid(Instant paidAt) {
    this.prizesPaid = true;
    this.prizesPaidAt = paidAt;
  }
}
