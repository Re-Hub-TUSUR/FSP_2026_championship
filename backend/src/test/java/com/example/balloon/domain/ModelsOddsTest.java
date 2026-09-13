package com.example.balloon.domain;

import com.example.balloon.domain.Models.Config;
import com.example.balloon.domain.Models.Theme;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Шансы по умолчанию: первый подъём без риска, коэффициенты заметно растут уже к середине полёта,
 * зелёный спокойнее красного, возврат игроку в коридоре для любой ставки и любого уровня, где разрешено забрать выигрыш.
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
  void firstRiskyLevelStaysPlayable() {
    assertTrue(Config.defaults(Theme.RED).hazardAt(2) <= 0.18);
    assertTrue(Config.defaults(Theme.GREEN).hazardAt(2) <= 0.13);
  }

  @Test
  void coefficientsDoubleByTheFifthLevel() {
    for (Theme theme : Theme.values()) {
      Config config = Config.defaults(theme);
      assertTrue(config.ladder()[5] >= 2, theme + ": ×2 не позже пятого уровня");
      for (double booster : config.boosters())
        assertTrue(config.ladderForBooster(booster)[6] >= 2, theme + " x" + booster + ": ×2 не позже шестого уровня");
    }
    Config red = Config.defaults(Theme.RED);
    Config green = Config.defaults(Theme.GREEN);
    assertTrue(red.ladder()[red.levels()] >= 100, "вершина красного шара — от ×100");
    assertTrue(green.ladder()[green.levels()] >= 20, "вершина зелёного шара — от ×20");
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
  void onlyUntouchedDefaultsOfPreviousVersionsAreMigrated() {
    // Шансы прошлых версий: первая версия и мягкий старт, отдельно для красной и зелёной темы.
    double[][][] previous = {{{0.12, 1.115}, {0.07, 1.20}}, {{0.155, 1.12}, {0.05, 1.35}}};
    for (Theme theme : Theme.values()) {
      Config fresh = Config.defaults(theme);
      for (double[] odds : previous[theme.ordinal()]) {
        Config legacy = stored(theme, odds[0], odds[1], new double[0], Config.PREVIOUS_MIN_WIN_AMOUNT);
        assertTrue(legacy.usesLegacyDefaultMath(), theme + " " + Arrays.toString(odds));

        Config migrated = legacy.withCurrentDefaults();
        assertEquals(fresh.baseHazard(), migrated.baseHazard(), 1e-9);
        assertEquals(fresh.hazardGrowth(), migrated.hazardGrowth(), 1e-9);
        assertEquals(0, migrated.boosterProbabilities()[0], 1e-9);
        assertEquals(fresh.minWinAmount(), migrated.minWinAmount(), "порог апсейла прошлых версий обновляется");
        assertArrayEquals(legacy.bets(), migrated.bets(), "ставки администратора сохраняются");
        assertEquals(legacy.version() + 1, migrated.version());
        assertFalse(migrated.needsDefaultsMigration());
      }
    }

    assertFalse(stored(Theme.GREEN, 0.2, 1.12, new double[0], 75).needsDefaultsMigration(),
        "настроенные администратором шансы и порог не трогаем");

    Config thresholdOnly = stored(Theme.GREEN, 0.2, 1.12, new double[0], Config.PREVIOUS_MIN_WIN_AMOUNT).withCurrentDefaults();
    assertEquals(0.2, thresholdOnly.baseHazard(), 1e-9, "при обновлении порога шансы администратора сохраняются");
    assertEquals(Config.DEFAULT_MIN_WIN_AMOUNT, thresholdOnly.minWinAmount());

    double[] manual = Arrays.copyOfRange(Config.defaults(Theme.GREEN).ladder(), 1, 10);
    assertFalse(stored(Theme.GREEN, 0.155, 1.12, manual, 75).usesLegacyDefaultMath(),
        "ручная лестница подобрана под старые шансы — их не трогаем");
  }

  private static Config stored(Theme theme, double baseHazard, double hazardGrowth, double[] ladder, long minWinAmount) {
    Config fresh = Config.defaults(theme);
    return new Config(theme, fresh.gameId(), fresh.gameName(), fresh.gameType(), true,
        fresh.levels(), new long[]{15, 30, 60, 120}, fresh.boosters(), Config.uniformWeights(fresh.levels()),
        10, 20, 15, baseHazard, hazardGrowth, 0.60, 0.96, ladder, minWinAmount, 10, 8);
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
