package com.example.balloon.application;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.domain.Models.Config;
import com.example.balloon.domain.Models.Round;
import com.example.balloon.domain.Models.Theme;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.NoSuchElementException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for server-side round access and start safeguards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:game-security-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class GameServiceSecurityIntegrationTest {
  @Autowired private GameService game;
  @Autowired private AccountService accounts;

  @Test
  void foreignPlayerCannotReadAnotherPlayersRound() {
    AccountEntity owner = player();
    AccountEntity stranger = player();
    Round round = game.start(owner.getUsername(), Theme.RED, "red-x1");

    assertThrows(NoSuchElementException.class, () -> game.getOwned(round.id, stranger.getUsername()));
  }

  @Test
  void rejectsBetIdsFromTheWrongThemeOrOutsideTheConfiguredFourOptions() {
    AccountEntity player = player();

    assertThrows(IllegalArgumentException.class, () -> game.start(player.getUsername(), Theme.RED, "green-x1"));
    assertThrows(IllegalArgumentException.class, () -> game.start(player.getUsername(), Theme.RED, "red-x5"));
    assertThrows(IllegalArgumentException.class, () -> game.start(player.getUsername(), Theme.RED, " red-x1"));
  }

  @Test
  void validatesAndPublishesPerLevelCrashProbabilities() {
    Config before = game.config(Theme.RED);
    double[] valid = new double[before.levels()];
    valid[0] = 0;
    Arrays.fill(valid, 1, valid.length, 0.25);

    assertThrows(IllegalArgumentException.class,
        () -> game.updateConfig(Theme.RED, withHazards(before, new double[before.levels() - 1]), "test"),
        "таблица должна содержать значение для каждого уровня");

    double[] firstLevelRisk = Arrays.copyOf(valid, valid.length);
    firstLevelRisk[0] = 0.01;
    assertThrows(IllegalArgumentException.class,
        () -> game.updateConfig(Theme.RED, withHazards(before, firstLevelRisk), "test"),
        "первый уровень обязан оставаться безопасным");

    try {
      Config updated = game.updateConfig(Theme.RED, withHazards(before, valid), "test");
      assertTrue(updated.hasCustomHazards());
      assertArrayEquals(valid, updated.hazardOverride());
      assertEquals(0, updated.hazardAt(1), 1e-12);
      assertEquals(0.25, updated.hazardAt(2), 1e-12);
    } finally {
      // Не оставлять изменённую конфигурацию другим тестам в этом общем Spring-контексте.
      game.updateConfig(Theme.RED, before, "test-restore");
    }
  }

  @Test
  void concurrentRetriesWithTheSameKeyCreateOnlyOneRoundAndOneDebit() throws Exception {
    AccountEntity player = player();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Round> first = pool.submit(() -> startAfterBarrier(player.getUsername(), ready, go));
      Future<Round> second = pool.submit(() -> startAfterBarrier(player.getUsername(), ready, go));
      assertTrue(ready.await(5, TimeUnit.SECONDS), "both requests should be ready before release");
      go.countDown();

      Round a = first.get(5, TimeUnit.SECONDS);
      Round b = second.get(5, TimeUnit.SECONDS);
      assertEquals(a.id, b.id);
      assertEquals(990, accounts.byUsername(player.getUsername()).getBalance());
    } finally {
      pool.shutdownNow();
    }
  }

  private Round startAfterBarrier(String username, CountDownLatch ready, CountDownLatch go) throws Exception {
    ready.countDown();
    if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("test start barrier timed out");
    return game.startIdempotent(username, Theme.RED, "red-x1", "parallel-retry-key");
  }

  private static Config withHazards(Config source, double[] hazards) {
    return new Config(source.theme(), source.gameId(), source.gameName(), source.gameType(), source.active(),
        source.levels(), source.bets(), source.boosters(), source.boosterWeights(),
        source.pointsPerLevel(), source.pointsCashoutBonus(), source.pointsBoosterBonus(),
        source.baseHazard(), source.hazardGrowth(), source.maxHazard(), hazards, source.rtp(),
        source.ladderOverride(), source.minWinAmount(), source.popupTimeout(), source.version());
  }

  private AccountEntity player() {
    String username = "game" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return accounts.register(username, "password");
  }
}
