package com.example.balloon.rounds;

import com.example.balloon.domain.Models.Config;
import com.example.balloon.domain.Models.PuzzleAward;
import com.example.balloon.domain.Models.Round;
import com.example.balloon.domain.Models.RoundState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Хранилище раундов: переводит игровой Round в запись базы и обратно. Изменения присоединяются
 * к транзакции вызывающего кода, поэтому запись раунда фиксируется вместе с начислением на аккаунт.
 */
@Component
public class RoundStore {
  private final RoundRepository rounds;
  private final ObjectMapper mapper;

  public RoundStore(RoundRepository rounds, ObjectMapper mapper) {
    this.rounds = rounds;
    this.mapper = mapper;
  }

  /** Раунд, найденный по ключу идемпотентности, и вариант ставки, с которым он стартовал. */
  public record StoredStart(Round round, String betOptionId) {}

  @Transactional
  public void insert(Round round, String betOptionId, String idempotencyKey) {
    rounds.save(new RoundEntity(round.id, round.userId, betOptionId, idempotencyKey, round.theme().name(),
        round.state.name(), round.betAmount, round.boosterMultiplier, round.boosterLevel, round.seed,
        round.commitment, round.popLevel, round.multiplier, json(round.ladder), json(round.config),
        round.balanceAfter, round.startedAt));
  }

  @Transactional
  public void update(String id, Consumer<RoundEntity> change) {
    change.accept(rounds.findById(id).orElseThrow(() -> new NoSuchElementException("Раунд не найден: " + id)));
  }

  @Transactional(readOnly = true)
  public Optional<Round> find(String id) {
    return rounds.findById(id).map(this::restore);
  }

  @Transactional(readOnly = true)
  public Optional<StoredStart> findStart(String username, String idempotencyKey) {
    return rounds.findByUsernameAndIdempotencyKey(username, idempotencyKey)
        .map(entity -> new StoredStart(restore(entity), entity.getBetOptionId()));
  }

  @Transactional(readOnly = true)
  public List<Round> recentFinished(int limit) {
    return rounds.findRecentFinished(PageRequest.of(0, limit)).stream().map(this::restore).toList();
  }

  @Transactional(readOnly = true)
  public List<Round> recentFinishedOf(String username, int limit) {
    return rounds.findRecentFinishedOf(username, PageRequest.of(0, limit)).stream().map(this::restore).toList();
  }

  @Transactional(readOnly = true)
  public List<Round> unsettled() {
    return rounds.findBySettlementCompleteFalseOrderByStartedAtAsc().stream().map(this::restore).toList();
  }

  public String json(Object value) {
    if (value == null) return null;
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Не удалось сохранить раунд: " + e.getMessage(), e);
    }
  }

  private Round restore(RoundEntity entity) {
    try {
      Config config = mapper.readValue(entity.getConfigJson(), Config.class);
      double[] ladder = mapper.readValue(entity.getLadderJson(), double[].class);
      Round round = new Round(entity.getId(), entity.getUsername(), config, entity.getBetAmount(),
          entity.getBoosterMultiplier(), entity.getSeed(), entity.getCommitment(), entity.getPopLevel(),
          entity.getBoosterLevel(), ladder, entity.getStartedAt(), entity.getBetOptionId());
      round.state = RoundState.valueOf(entity.getState());
      round.level = entity.getLevel();
      round.multiplier = entity.getMultiplier();
      round.cashedOutMultiplier = entity.getCashedOutMultiplier();
      round.payout = entity.getPayout();
      round.points = entity.getPoints();
      round.boosterActivated = entity.isBoosterActivated();
      round.balanceAfter = entity.getBalanceAfter();
      round.winNumber = entity.getWinNumber();
      round.endedAt = entity.getEndedAt();
      round.flightOver = entity.isFlightOver();
      round.reward = entity.getRewardJson() == null ? null : mapper.readValue(entity.getRewardJson(), PuzzleAward.class);
      round.pointsSettled = entity.isPointsSettled();
      round.rewardSettled = entity.isRewardSettled();
      round.winSettled = entity.isWinSettled();
      round.settlementComplete = entity.isSettlementComplete();
      return round;
    } catch (Exception e) {
      throw new IllegalStateException("Не удалось прочитать раунд " + entity.getId() + ": " + e.getMessage(), e);
    }
  }
}
