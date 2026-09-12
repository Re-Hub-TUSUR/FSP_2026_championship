package com.example.balloon.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Arrays;

public final class Models {
  public static final double MAX_MULTIPLIER = 10_000;

  public enum Theme { RED, GREEN }
  public enum RoundState { CREATED, STARTED, CASHED_OUT, CRASHED, SETTLED }

  /**
   * Настройки игры, которыми управляет администратор без выпуска новой версии.
   * Группы соответствуют п. 1.9 постановки задачи: базовые поля, параметры
   * математической модели, положение бустера по уровням, множители бустеров и очки.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Config(Theme theme, String gameId, String gameName, String gameType, boolean active,
                       int levels, long[] bets, double[] boosters, double[] boosterWeights,
                       int pointsPerLevel, int pointsCashoutBonus, int pointsBoosterBonus,
                       double baseHazard, double hazardGrowth, double maxHazard, double rtp,
                       double[] ladderOverride, long version) {

    public static Config defaults(Theme theme) {
      int levels = theme == Theme.RED ? 12 : 9;
      return new Config(theme, "balloon", "Воздушный шар", "CRASH", true,
          levels, new long[]{10, 20, 50, 100}, new double[]{1, 2, 3, 4}, uniformWeights(levels),
          10, 20, 15,
          theme == Theme.RED ? 0.12 : 0.155, theme == Theme.RED ? 1.115 : 1.12, 0.60, 0.96,
          new double[0], 1);
    }

    public static double[] uniformWeights(int levels) {
      double[] weights = new double[Math.max(1, levels)];
      Arrays.fill(weights, 1.0 / weights.length);
      return weights;
    }

    /** Шанс взрыва на уровне: давление в шаре растёт экспоненциально с каждым уровнем. */
    public double hazardAt(int level) {
      return Math.min(maxHazard, baseHazard * Math.pow(hazardGrowth, level - 1));
    }

    /** Шанс долететь до уровня включительно. */
    public double survivalAt(int level) {
      double survival = 1;
      for (int i = 1; i <= level; i++) survival *= 1 - hazardAt(i);
      return survival;
    }

    /**
     * Лестница по формуле: коэффициент уровня обратен шансу до него долететь,
     * поэтому растёт экспоненциально, а возврат игроку одинаков на любом уровне.
     */
    public double[] modelLadder() {
      double[] ladder = new double[levels + 1];
      ladder[0] = 1.0;
      double survival = 1.0;
      for (int level = 1; level <= levels; level++) {
        survival *= 1 - hazardAt(level);
        // Потолок нужен для конфигураций админки с большим числом уровней: без него хвост лестницы уходит в миллионы.
        ladder[level] = Math.min(MAX_MULTIPLIER, Math.round(Math.max(ladder[level - 1] + 0.01, rtp / survival) * 100.0) / 100.0);
      }
      return ladder;
    }

    public boolean hasCustomLadder() { return ladderOverride != null && ladderOverride.length == levels; }

    /** Действующая лестница: значения, заданные администратором вручную, либо расчёт по формуле. */
    public double[] ladder() {
      if (!hasCustomLadder()) return modelLadder();
      double[] ladder = new double[levels + 1];
      ladder[0] = 1.0;
      System.arraycopy(ladderOverride, 0, ladder, 1, levels);
      return ladder;
    }

    /** Вероятность выпадения бустера на уровень (line_N_loot_prob), нормированная к единице. */
    public double[] boosterProbabilities() {
      if (boosterWeights == null || boosterWeights.length != levels) return uniformWeights(levels);
      double sum = 0;
      for (double weight : boosterWeights) sum += Math.max(0, weight);
      if (sum <= 0) return uniformWeights(levels);
      double[] probabilities = new double[levels];
      for (int i = 0; i < levels; i++) probabilities[i] = Math.max(0, boosterWeights[i]) / sum;
      return probabilities;
    }

    /** Ожидаемый возврат при выходе на уровне: шанс долететь × коэффициент уровня. */
    public double expectedReturnAt(int level) { return survivalAt(level) * ladder()[level]; }
  }

  public static class Round {
    public final String id, userId;
    public final Config config;
    public final long betAmount;
    public final double boosterMultiplier;
    public final String seed, commitment;
    public final int popLevel, boosterLevel;
    public final double[] ladder;
    public final Instant startedAt = Instant.now();

    public volatile int level = 0;
    public volatile double multiplier = 1.0;
    public volatile RoundState state = RoundState.STARTED;
    public volatile Double cashedOutMultiplier;
    public volatile long payout = 0;
    public volatile int points = 0;
    public volatile boolean boosterActivated = false;
    public volatile long balanceAfter;
    public volatile Instant endedAt;

    public Round(String id, String userId, Config config, long betAmount, double boosterMultiplier,
                 String seed, String commitment, int popLevel, int boosterLevel, double[] ladder) {
      this.id = id;
      this.userId = userId;
      this.config = config;
      this.betAmount = betAmount;
      this.boosterMultiplier = boosterMultiplier;
      this.seed = seed;
      this.commitment = commitment;
      this.popLevel = popLevel;
      this.boosterLevel = boosterLevel;
      this.ladder = ladder;
    }

    public Theme theme() { return config.theme(); }
    public int levels() { return config.levels(); }
    public boolean finished() { return state == RoundState.CRASHED || state == RoundState.CASHED_OUT || state == RoundState.SETTLED; }
    public double nextMultiplier() { return level < levels() ? ladder[level + 1] : ladder[levels()]; }
    public long profit() { return payout - betAmount; }
  }
}
