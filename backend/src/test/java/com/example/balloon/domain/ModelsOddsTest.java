package com.example.balloon.domain;

import com.example.balloon.domain.Models.Config;
import com.example.balloon.domain.Models.Theme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Шансы по умолчанию: первый подъём без риска, зелёный спокойнее красного,
 * возврат игроку в коридоре для любой ставки и любого уровня, где разрешено забрать выигрыш.
 */
class ModelsOddsTest {

  @Test
  void balloonNeverPopsOnTheFirstLevel() {
    for (Theme theme : Theme.values()) assertEquals(0, Config.defaults(theme).hazardAt(1), 1e-12);
    Config risky = withHazard(Config.defaults(Theme.RED), 0.5, 1.5);
    assertEquals(0, risky.hazardAt(1), 1e-12, "правило действует и для настроек администратора");
    assertTrue(risky.hazardAt(2) > 0);
    assertEquals(Models.SAFE_LEVELS + 1, Models.MIN_CASHOUT_LEVEL);
  }

  @Test
  void firstRiskyLevelKeepsASoftStart() {
    assertTrue(Config.defaults(Theme.RED).hazardAt(2) <= 0.085);
    assertTrue(Config.defaults(Theme.GREEN).hazardAt(2) <= 0.07);
  }

  @Test
  void greenIsCalmerThanRedOnTheFirstRiskyLevels() {
    Config red = Config.defaults(Theme.RED);
    Config green = Config.defaults(Theme.GREEN);
    for (int level = Models.MIN_CASHOUT_LEVEL; level <= 3; level++)
      assertTrue(green.hazardAt(level) < red.hazardAt(level), "уровень " + level);
    assertTrue(green.survivalAt(green.levels()) > red.survivalAt(red.levels()),
        "зелёный должен чаще долетать до вершины");
  }

  @Test
  void boosterNeverLandsBeforeItsFirstLevel() {
    for (Theme theme : Theme.values()) {
      Config fresh = Config.defaults(theme);
      Config uniform = withWeights(fresh, Config.uniformWeights(fresh.levels()));
      for (Config config : new Config[]{fresh, uniform}) {
        double[] probabilities = config.boosterProbabilities();
        double total = 0;
        for (int level = 1; level <= config.levels(); level++) {
          if (level < Models.FIRST_BOOSTER_LEVEL)
            assertEquals(0, probabilities[level - 1], 1e-12, theme + ": бустер на уровне " + level);
          total += probabilities[level - 1];
        }
        assertEquals(1, total, 1e-9);
      }
    }
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
          if (level < Models.MIN_CASHOUT_LEVEL) continue;
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

  private static Config withHazard(Config source, double baseHazard, double hazardGrowth) {
    return new Config(source.theme(), source.gameId(), source.gameName(), source.gameType(), source.active(),
        source.levels(), source.bets(), source.boosters(), source.boosterWeights(),
        source.pointsPerLevel(), source.pointsCashoutBonus(), source.pointsBoosterBonus(),
        baseHazard, hazardGrowth, 0.95, source.rtp(),
        source.ladderOverride(), source.minWinAmount(), source.popupTimeout(), source.version());
  }

  private static Config withWeights(Config source, double[] weights) {
    return new Config(source.theme(), source.gameId(), source.gameName(), source.gameType(), source.active(),
        source.levels(), source.bets(), source.boosters(), weights,
        source.pointsPerLevel(), source.pointsCashoutBonus(), source.pointsBoosterBonus(),
        source.baseHazard(), source.hazardGrowth(), source.maxHazard(), source.rtp(),
        source.ladderOverride(), source.minWinAmount(), source.popupTimeout(), source.version());
  }
}
