package com.example.balloon.rounds;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Раунд игрока в базе: ставка, исход, выплата, очки и данные для проверки честности.
 * Запись создаётся в одной транзакции со списанием ставки и меняется вместе с каждым начислением
 * на аккаунт, поэтому история, рейтинг и баланс переживают перезапуск сервера и не расходятся.
 */
@Entity
@DynamicUpdate
@Table(name = "rounds",
    uniqueConstraints = @UniqueConstraint(name = "uk_rounds_username_idempotency_key", columnNames = {"username", "idempotency_key"}),
    indexes = {
        @Index(name = "idx_rounds_username_started", columnList = "username,started_at"),
        @Index(name = "idx_rounds_started", columnList = "started_at"),
        @Index(name = "idx_rounds_settlement", columnList = "settlement_complete")
    })
public class RoundEntity implements Persistable<String> {
  /** Полёт прерван перезапуском сервера до cashout: раунд отменён, ставка возвращена. */
  public static final String REFUNDED = "REFUNDED";

  @Id
  @Column(name = "id", length = 36)
  private String id;

  @Column(name = "username", nullable = false, length = 80)
  private String username;

  @Column(name = "bet_option_id", nullable = false, length = 32)
  private String betOptionId;

  /** Ключ идемпотентного старта: повтор запроса и после перезапуска возвращает этот же раунд. */
  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @Column(name = "theme", nullable = false, length = 16)
  private String theme;

  @Column(name = "state", nullable = false, length = 16)
  private String state;

  @Column(name = "bet_amount", nullable = false)
  private long betAmount;

  @Column(name = "booster_multiplier", nullable = false)
  private double boosterMultiplier;

  @Column(name = "booster_level", nullable = false)
  private int boosterLevel;

  @Column(name = "booster_activated", nullable = false)
  private boolean boosterActivated;

  @Column(name = "seed", nullable = false, length = 64)
  private String seed;

  @Column(name = "commitment", nullable = false, length = 64)
  private String commitment;

  @Column(name = "pop_level", nullable = false)
  private int popLevel;

  @Column(name = "level", nullable = false)
  private int level;

  @Column(name = "multiplier", nullable = false)
  private double multiplier;

  @Column(name = "cashed_out_multiplier")
  private Double cashedOutMultiplier;

  @Column(name = "payout", nullable = false)
  private long payout;

  @Column(name = "points", nullable = false)
  private int points;

  @Column(name = "balance_after", nullable = false)
  private long balanceAfter;

  @Column(name = "win_number", nullable = false)
  private int winNumber;

  @Column(name = "flight_over", nullable = false)
  private boolean flightOver;

  @Column(name = "points_settled", nullable = false)
  private boolean pointsSettled;

  @Column(name = "reward_settled", nullable = false)
  private boolean rewardSettled;

  @Column(name = "win_settled", nullable = false)
  private boolean winSettled;

  /** Все начисления по раунду выполнены; незавершённые записи сервис доводит при старте. */
  @Column(name = "settlement_complete", nullable = false)
  private boolean settlementComplete;

  /** Лестница коэффициентов тира, по которой летел шар. */
  @Column(name = "ladder_json", nullable = false, length = 1000)
  private String ladderJson;

  /** Снимок настроек темы на момент старта: по нему раунд доигрывается и проверяется, даже если администратор их поменял. */
  @Lob
  @Column(name = "config_json", nullable = false)
  private String configJson;

  /** Фрагмент ежедневного пазла, выданный за раунд; null — фрагмента не было. */
  @Column(name = "reward_json", length = 255)
  private String rewardJson;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  /** Идентификатор задаёт сервер, поэтому новизну записи Spring Data узнаёт отсюда, а не по пустому id. */
  @Transient
  private boolean isNew;

  protected RoundEntity() {}

  public RoundEntity(String id, String username, String betOptionId, String idempotencyKey, String theme, String state,
                     long betAmount, double boosterMultiplier, int boosterLevel, String seed, String commitment,
                     int popLevel, double multiplier, String ladderJson, String configJson, long balanceAfter,
                     Instant startedAt) {
    this.id = id;
    this.username = username;
    this.betOptionId = betOptionId;
    this.idempotencyKey = idempotencyKey;
    this.theme = theme;
    this.state = state;
    this.betAmount = betAmount;
    this.boosterMultiplier = boosterMultiplier;
    this.boosterLevel = boosterLevel;
    this.seed = seed;
    this.commitment = commitment;
    this.popLevel = popLevel;
    this.multiplier = multiplier;
    this.ladderJson = ladderJson;
    this.configJson = configJson;
    this.balanceAfter = balanceAfter;
    this.startedAt = startedAt;
    this.isNew = true;
  }

  /** Выплата при cashout или на вершине: фиксируется вместе с начислением и счётчиком выигрышей. */
  public void recordPayout(String state, double cashedOutMultiplier, long payout, int points, long balanceAfter,
                           int level, double multiplier, boolean boosterActivated, int winNumber) {
    this.state = state;
    this.cashedOutMultiplier = cashedOutMultiplier;
    this.payout = payout;
    this.points = points;
    this.balanceAfter = balanceAfter;
    this.level = level;
    this.multiplier = multiplier;
    this.boosterActivated = boosterActivated;
    this.winNumber = winNumber;
    this.winSettled = true;
  }

  public void recordFlightEnd(String state, int level, double multiplier, int points, boolean boosterActivated, Instant endedAt) {
    this.state = state;
    this.level = level;
    this.multiplier = multiplier;
    this.points = points;
    this.boosterActivated = boosterActivated;
    this.endedAt = endedAt;
    this.flightOver = true;
  }

  public void markPointsSettled() { this.pointsSettled = true; }

  public void recordReward(String rewardJson) {
    this.rewardJson = rewardJson;
    this.rewardSettled = true;
  }

  public void markRewardSettled() { this.rewardSettled = true; }

  public void completeSettlement(long balanceAfter) {
    this.balanceAfter = balanceAfter;
    this.settlementComplete = true;
  }

  /** Отмена раунда без cashout: выплата равна ставке, очков и фрагмента пазла нет. */
  public void refund(long balanceAfter, Instant endedAt) {
    this.state = REFUNDED;
    this.payout = betAmount;
    this.cashedOutMultiplier = null;
    this.points = 0;
    this.multiplier = 1.0;
    this.balanceAfter = balanceAfter;
    this.endedAt = endedAt;
    this.flightOver = true;
    this.pointsSettled = true;
    this.rewardSettled = true;
    this.winSettled = true;
    this.settlementComplete = true;
  }

  @PostLoad
  @PostPersist
  void markNotNew() { this.isNew = false; }

  @Override public String getId() { return id; }
  @Override public boolean isNew() { return isNew; }

  public String getUsername() { return username; }
  public String getBetOptionId() { return betOptionId; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getTheme() { return theme; }
  public String getState() { return state; }
  public long getBetAmount() { return betAmount; }
  public double getBoosterMultiplier() { return boosterMultiplier; }
  public int getBoosterLevel() { return boosterLevel; }
  public boolean isBoosterActivated() { return boosterActivated; }
  public String getSeed() { return seed; }
  public String getCommitment() { return commitment; }
  public int getPopLevel() { return popLevel; }
  public int getLevel() { return level; }
  public double getMultiplier() { return multiplier; }
  public Double getCashedOutMultiplier() { return cashedOutMultiplier; }
  public long getPayout() { return payout; }
  public int getPoints() { return points; }
  public long getBalanceAfter() { return balanceAfter; }
  public int getWinNumber() { return winNumber; }
  public boolean isFlightOver() { return flightOver; }
  public boolean isPointsSettled() { return pointsSettled; }
  public boolean isRewardSettled() { return rewardSettled; }
  public boolean isWinSettled() { return winSettled; }
  public boolean isSettlementComplete() { return settlementComplete; }
  public String getLadderJson() { return ladderJson; }
  public String getConfigJson() { return configJson; }
  public String getRewardJson() { return rewardJson; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getEndedAt() { return endedAt; }
}
