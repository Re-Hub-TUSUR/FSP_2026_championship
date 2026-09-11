package com.example.balloon.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

public final class Models {
  public static final double MAX_MULTIPLIER = 10_000;

  public enum Theme { RED, GREEN }
  public enum RoundState { CREATED, STARTED, CASHED_OUT, CRASHED, SETTLED }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Config(Theme theme, int levels, long[] bets, double[] boosters,
                       int pointsPerLevel, int pointsCashoutBonus, int pointsBoosterBonus,
                       double baseHazard, double hazardGrowth, double maxHazard, double rtp,
                       long version) {

    public static Config defaults(Theme t) {
      return t == Theme.RED
          ? new Config(t, 12, new long[]{10, 20, 50, 100}, new double[]{1, 2, 3, 4}, 10, 20, 15, 0.12, 1.115, 0.60, 0.96, 1)
          : new Config(t, 9, new long[]{10, 20, 50, 100}, new double[]{1, 2, 3, 4}, 10, 20, 15, 0.155, 1.12, 0.60, 0.96, 1);
    }

    /** Шанс взрыва на уровне: давление в шаре растёт экспоненциально с каждым уровнем. */
    public double hazardAt(int level) {
      return Math.min(maxHazard, baseHazard * Math.pow(hazardGrowth, level - 1));
    }

    /**
     * Лестница коэффициентов: коэффициент уровня обратен шансу до него долететь,
     * поэтому рост экспоненциальный и ускоряется вместе с риском. Множитель rtp
     * задаёт возврат игроку и одинаков на любом уровне.
     */
    public double[] ladder() {
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
