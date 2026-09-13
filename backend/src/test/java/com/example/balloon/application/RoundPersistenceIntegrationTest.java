package com.example.balloon.application;

import com.example.balloon.auth.AccountService;
import com.example.balloon.config.GameConfigRepository;
import com.example.balloon.domain.Models.Round;
import com.example.balloon.domain.Models.RoundState;
import com.example.balloon.domain.Models.Theme;
import com.example.balloon.rounds.RoundEntity;
import com.example.balloon.rounds.RoundRepository;
import com.example.balloon.rounds.RoundStore;
import com.example.balloon.tournament.TournamentEntity;
import com.example.balloon.tournament.TournamentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раунды хранятся в базе: история, рейтинг и турнир переживают перезапуск сервиса, а раунды,
 * прерванные перезапуском, доигрываются по сиду или отменяются с возвратом ставки.
 * Перезапуск моделируется новым экземпляром GameService поверх той же базы.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:round-persistence-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    // Такты двигает сам тест, фоновый таймер не должен вмешиваться.
    "game.tick-ms=3600000"
})
class RoundPersistenceIntegrationTest {
  private static final long START_BALANCE = 1000;
  private static final long IDLE_TICK_MS = 3_600_000;

  @Autowired private GameService game;
  @Autowired private AccountService accounts;
  @Autowired private GameConfigRepository configs;
  @Autowired private RoundStore store;
  @Autowired private RoundRepository roundRepository;
  @Autowired private TournamentRepository tournaments;
  @Autowired private ObjectMapper mapper;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void finishedRoundSurvivesRestartInHistoryAndProof() {
    String player = player();
    Round round = startRound(player, 3);
    tickTo(round, 2);
    game.cashoutOwned(round.id, player);
    flyToEnd(round);

    GameService restarted = restart();
    try {
      Round stored = restarted.historyOf(player, 100).stream()
          .filter(candidate -> candidate.id.equals(round.id)).findFirst().orElseThrow();
      assertEquals(RoundState.CASHED_OUT, stored.state);
      assertEquals(round.payout, stored.payout);
      assertEquals(round.points, stored.points);
      assertEquals(round.level, stored.level);
      assertEquals(round.cashedOutMultiplier, stored.cashedOutMultiplier);
      assertEquals(round.reward, stored.reward, "фрагмент пазла хранится вместе с раундом");
      assertTrue(restarted.history(100).stream().anyMatch(candidate -> candidate.id.equals(round.id)), "раунд виден в общей ленте");

      Map<String, Object> proof = restarted.proofOwned(round.id, player);
      assertEquals(round.seed, proof.get("seed"));
      assertEquals(round.popLevel, proof.get("popLevel"));
      assertBalanceAndPointsMatchRounds(player);
    } finally {
      restarted.shutdown();
    }
  }

  @Test
  void restartRefundsFlightWithoutCashoutAndFinishesSecuredFlight() {
    String player = player();
    Round interrupted = startRound(player, 3);
    tickTo(interrupted, 2);

    Round secured = startRound(player, 4);
    tickTo(secured, 2);
    game.cashoutOwned(secured.id, player);

    GameService restarted = restart();
    try {
      RoundEntity refunded = roundRepository.findById(interrupted.id).orElseThrow();
      assertEquals(RoundEntity.REFUNDED, refunded.getState());
      assertTrue(refunded.isFlightOver() && refunded.isSettlementComplete());
      assertEquals(interrupted.betAmount, refunded.getPayout(), "ставка возвращена");
      assertEquals(0, refunded.getPoints());

      RoundEntity finished = roundRepository.findById(secured.id).orElseThrow();
      assertEquals(RoundState.CASHED_OUT.name(), finished.getState());
      assertTrue(finished.isFlightOver() && finished.isSettlementComplete());
      assertEquals(Math.min(secured.popLevel - 1, secured.levels()), finished.getLevel(), "полёт доведён до точки взрыва по сиду");
      assertEquals(secured.payout, finished.getPayout(), "зафиксированный выигрыш остаётся за игроком");

      assertTrue(restarted.historyOf(player, 100).stream()
          .anyMatch(round -> round.id.equals(interrupted.id) && round.state == RoundState.REFUNDED));
      assertTrue(restarted.history(100).stream().noneMatch(round -> round.id.equals(interrupted.id)),
          "отменённый раунд не попадает в общую ленту");
      assertBalanceAndPointsMatchRounds(player);
    } finally {
      restarted.shutdown();
    }
  }

  @Test
  void retryWithSameIdempotencyKeyAfterRestartDoesNotDebitTwice() {
    String player = player();
    Round first = game.startIdempotent(player, Theme.RED, "red-x1", "retry-after-restart");

    GameService restarted = restart();
    try {
      Round retry = restarted.startIdempotent(player, Theme.RED, "red-x1", "retry-after-restart");
      assertEquals(first.id, retry.id, "повтор запроса не создаёт второй раунд");
      assertEquals(RoundState.REFUNDED, retry.state, "раунд без cashout отменён перезапуском");
      assertEquals(START_BALANCE, accounts.byUsername(player).getBalance(), "ставка списана один раз и возвращена");
      assertThrows(IllegalArgumentException.class,
          () -> restarted.startIdempotent(player, Theme.RED, "red-x2", "retry-after-restart"));
    } finally {
      restarted.shutdown();
    }
  }

  @Test
  void leaderboardCountsRoundsFromDatabase() {
    String player = player();
    Round won = startRound(player, 3);
    tickTo(won, 2);
    game.cashoutOwned(won.id, player);
    flyToEnd(won);
    flyToEnd(game.start(player, Theme.GREEN, "green-x1"));

    List<RoundEntity> rows = roundsOf(player);
    LeaderboardService afterRestart = new LeaderboardService(accounts, roundRepository, tournaments, transactionManager);
    LeaderboardService.Entry entry = afterRestart.entries(player, "score", 0, 10).stream()
        .filter(candidate -> candidate.username().equals(player)).findFirst().orElseThrow();
    assertEquals(rows.size(), entry.rounds());
    assertEquals(rows.stream().filter(row -> row.getPayout() > 0).count(), entry.cashouts());
    assertEquals(accounts.byUsername(player).getPoints(), entry.points());
    double best = rows.stream().map(RoundEntity::getCashedOutMultiplier).filter(Objects::nonNull)
        .mapToDouble(Double::doubleValue).max().orElse(1.0);
    assertEquals(best, entry.multiplier(), 1e-9);
  }

  @Test
  void tournamentSurvivesRestartAndPaysPrizesOnce() {
    tournaments.deleteAll();
    Instant now = Instant.now();
    tournaments.save(new TournamentEntity(now.minus(Duration.ofDays(15)), now.minus(Duration.ofMinutes(1))));

    LeaderboardService beforeRestart = new LeaderboardService(accounts, roundRepository, tournaments, transactionManager);
    LeaderboardService afterRestart = new LeaderboardService(accounts, roundRepository, tournaments, transactionManager);
    assertEquals(beforeRestart.tournament().startedAt(), afterRestart.tournament().startedAt(), "перезапуск не начинает турнир заново");

    List<LeaderboardService.Entry> top = beforeRestart.entries("", "score", 0, 3);
    Map<String, Long> balances = top.stream()
        .collect(Collectors.toMap(LeaderboardService.Entry::id, entry -> accounts.byId(entry.id()).getBalance()));

    beforeRestart.settleTournament();
    afterRestart.settleTournament();

    long[] prizes = {1000, 500, 250};
    for (int i = 0; i < top.size(); i++)
      assertEquals(balances.get(top.get(i).id()) + prizes[i], accounts.byId(top.get(i).id()).getBalance(),
          "приз за " + (i + 1) + " место начислен один раз");
    assertTrue(afterRestart.tournament().prizesPaid());
  }

  /** Раунд, в котором шар проходит хотя бы minPopLevel − 1 уровней; неподходящие раунды доигрываются до конца. */
  private Round startRound(String player, int minPopLevel) {
    for (int attempt = 0; attempt < 50; attempt++) {
      Round round = game.start(player, Theme.RED, "red-x1");
      if (round.popLevel >= minPopLevel) return round;
      flyToEnd(round);
    }
    throw new AssertionError("Не удалось получить раунд, где шар долетает до уровня " + (minPopLevel - 1));
  }

  private void tickTo(Round round, int level) {
    for (int i = 0; i < 20 && round.level < level; i++) game.tick(round);
    assertEquals(level, round.level);
  }

  private void flyToEnd(Round round) {
    for (int i = 0; i < 40 && !round.settlementComplete; i++) game.tick(round);
    assertTrue(round.settlementComplete, "раунд " + round.id + " должен завершиться");
  }

  /** Новый экземпляр сервиса поверх той же базы — так выглядит перезапуск backend. */
  private GameService restart() {
    GameService restarted = new GameService(accounts, configs, mapper, store, transactionManager, IDLE_TICK_MS);
    restarted.run();
    return restarted;
  }

  private List<RoundEntity> roundsOf(String player) {
    return roundRepository.findAll().stream().filter(row -> row.getUsername().equals(player)).toList();
  }

  /** Баланс равен стартовому минус ставки плюс выплаты, очки — сумме очков раундов с подведёнными итогами. */
  private void assertBalanceAndPointsMatchRounds(String player) {
    List<RoundEntity> rows = roundsOf(player);
    long expectedBalance = START_BALANCE - rows.stream().mapToLong(RoundEntity::getBetAmount).sum()
        + rows.stream().mapToLong(RoundEntity::getPayout).sum();
    long expectedPoints = rows.stream().filter(RoundEntity::isPointsSettled).mapToLong(RoundEntity::getPoints).sum();
    assertEquals(expectedBalance, accounts.byUsername(player).getBalance(), "баланс сходится с раундами в базе");
    assertEquals(expectedPoints, accounts.byUsername(player).getPoints(), "очки сходятся с раундами в базе");
  }

  private String player() {
    String username = "rounds" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return accounts.register(username, "password").getUsername();
  }
}
