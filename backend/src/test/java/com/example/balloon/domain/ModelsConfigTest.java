package com.example.balloon.domain;

import com.example.balloon.domain.Models.Config;
import com.example.balloon.domain.Models.Round;
import com.example.balloon.domain.Models.Theme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Небольшие доменные проверки, не требующие Spring-контекста или базы данных.
 * Они фиксируют базовые параметры из постановки и серверную семантику бустера.
 */
class ModelsConfigTest {

  @Test
  void defaultThemesHaveRequiredLevelCounts() {
    assertEquals(12, Config.defaults(Theme.RED).levels());
    assertEquals(9, Config.defaults(Theme.GREEN).levels());
  }

  @Test
  void generatedLaddersStartAtOneAndGrowStrictly() {
    for (Theme theme : Theme.values()) {
      Config config = Config.defaults(theme);
      double[] ladder = config.ladder();

      assertEquals(config.levels() + 1, ladder.length,
          "Лестница должна включать стартовое значение и все уровни");
      assertEquals(1.0, ladder[0], 1e-9);
      for (int level = 1; level < ladder.length; level++) {
        int checkedLevel = level;
        assertTrue(ladder[level] > ladder[level - 1],
            theme + ": коэффициент уровня " + checkedLevel + " не растёт");
      }
    }
  }

  @Test
  void activatedBoosterChangesCrashMultiplier() {
    Config config = Config.defaults(Theme.RED);
    double[] ladder = config.ladderForBooster(2.0);
    int boosterLevel = 2;
    int popLevel = 5;
    Round round = new Round(
        "test-round",
        "test-user",
        config,
        10,
        2.0,
        "test-seed",
        "test-commitment",
        popLevel,
        boosterLevel,
        ladder);

    round.boosterActivated = true;

    assertEquals(round.multiplierAt(popLevel), round.crashMultiplier(), 1e-9);
    assertTrue(round.crashMultiplier() > ladder[popLevel],
        "После активации бустера crashMultiplier должен учитывать его множитель");
  }
}
