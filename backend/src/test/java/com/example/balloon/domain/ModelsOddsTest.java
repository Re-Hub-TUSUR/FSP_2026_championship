package com.example.balloon.domain;

import com.example.balloon.domain.Models.Config;
import com.example.balloon.domain.Models.Theme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шансы по умолчанию: мягкий старт, зелёный спокойнее красного, возврат игроку в коридоре для всех ставок. */
class ModelsOddsTest {

  @Test
  void balloonRarelyPopsBeforeThePlayerCanAct() {
    assertTrue(Config.defaults(Theme.RED).hazardAt(1) <= 0.07);
    assertTrue(Config.defaults(Theme.GREEN).hazardAt(1) <= 0.05);
  }

  @Test
  void greenIsCalmerThanRedOnTheFirstLevels() {
    Config red = Config.defaults(Theme.RED);
    Config green = Config.defaults(Theme.GREEN);
    for (int level = 1; level <= 3; level++)
      assertTrue(green.hazardAt(level) < red.hazardAt(level), "уровень " + level);
    assertTrue(green.survivalAt(green.levels()) > red.survivalAt(red.levels()),
        "зелёный должен чаще долетать до вершины");
  }

  @Test
  void everyBetOptionKeepsReturnToPlayerInCorridor() {
    for (Theme theme : Theme.values()) {
      Config config = Config.defaults(theme);
      double[] probabilities = config.boosterProbabilities();
      for (double booster : config.boosters()) {
        double[] ladder = config.ladderForBooster(booster);
        double boost = config.boosterAppearChance(booster) * (booster - 1);
        double reached = 0;
        for (int level = 1; level <= config.levels(); level++) {
          reached += probabilities[level - 1];
          double expected = config.survivalAt(level) * ladder[level] * (1 + boost * reached);
          assertTrue(expected > 0.95 && expected < 0.97,
              theme + " x" + booster + " уровень " + level + ": возврат " + expected);
        }
      }
    }
  }

  @Test
  void onlyUntouchedLegacyOddsAreMigrated() {
    Config fresh = Config.defaults(Theme.GREEN);
    Config legacy = new Config(Theme.GREEN, fresh.gameId(), fresh.gameName(), fresh.gameType(), true,
        9, new long[]{15, 30, 60, 120}, fresh.boosters(), Config.uniformWeights(9),
        10, 20, 15, 0.155, 1.12, 0.60, 0.96, new double[0], 50, 10, 8);
    assertTrue(legacy.usesLegacyDefaultMath());

    Config migrated = legacy.withDefaultMath();
    assertEquals(fresh.baseHazard(), migrated.baseHazard(), 1e-9);
    assertEquals(fresh.hazardGrowth(), migrated.hazardGrowth(), 1e-9);
    assertEquals(0, migrated.boosterProbabilities()[0], 1e-9);
    assertArrayEquals(legacy.bets(), migrated.bets(), "ставки администратора сохраняются");
    assertEquals(9, migrated.version());
    assertFalse(migrated.usesLegacyDefaultMath());

    Config tuned = new Config(Theme.GREEN, fresh.gameId(), fresh.gameName(), fresh.gameType(), true,
        9, fresh.bets(), fresh.boosters(), Config.uniformWeights(9),
        10, 20, 15, 0.2, 1.12, 0.60, 0.96, new double[0], 50, 10, 3);
    assertFalse(tuned.usesLegacyDefaultMath(), "настроенные администратором шансы не трогаем");
  }
}
