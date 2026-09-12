package com.example.balloon.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Arrays;

public final class Models {
  public static final double MAX_MULTIPLIER = 10_000;

  /**
   * Ожидаемая прибавка коэффициента от бустера, одинаковая для всех тиров.
   * Чем сильнее бустер, тем реже он выпадает: (множитель − 1) × шанс появления = BOOSTER_BUDGET.
   * Благодаря этому возврат игроку не зависит от выбранного варианта ставки.
   */
  public static final double BOOSTER_BUDGET = 0.5;

  public enum Theme { RED, GREEN }
  public enum RoundState { CREATED, STARTED, CASHED_OUT, CRASHED, SETTLED }

  /** Награда за раунд: фрагмент пазла и состояние сегодняшнего лотерейного билета. */
  public record PuzzleAward(int fragment, int collected, int total, boolean completed, long bonus,
                            boolean ticketEligible) {}

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
                       double[] ladderOverride, long minWinAmount, int popupTimeout, long version) {

    public static Config defaults(Theme theme) {
      int levels = theme == Theme.RED ? 12 : 9;
      return new Config(theme, "balloon", "Воздушный шар", "CRASH", true,
          levels, new long[]{10, 20, 50, 100}, new double[]{1, 2, 3, 4}, uniformWeights(levels),
          10, 20, 15,
          theme == Theme.RED ? 0.12 : 0.155, theme == Theme.RED ? 1.115 : 1.12, 0.60, 0.96,
          new double[0], 50, 10, 1);
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

    /** Шанс, что бустер вообще появится в раунде: сильный бустер выпадает реже. */
    public double boosterAppearChance(double booster) {
      return booster <= 1 ? 0 : Math.min(1, BOOSTER_BUDGET / (booster - 1));
    }

    /**
     * Лестница для ставки с бустером. Ожидаемый вклад бустера заранее вычитается из
     * базовых коэффициентов, поэтому возврат игроку одинаков для всех вариантов ставки.
     * Вклад одинаков для всех тиров, значит и лестница у них общая.
     */
    public double[] ladderForBooster(double booster) {
      double[] base = ladder();
      if (booster <= 1) return base;
      double[] probabilities = boosterProbabilities();
      double[] priced = new double[base.length];
      priced[0] = 1.0;
      double reached = 0;
      for (int level = 1; level <= levels; level++) {
        reached += probabilities[level - 1];
        double value = Math.round(base[level] / (1 + BOOSTER_BUDGET * reached) * 100.0) / 100.0;
        priced[level] = Math.max(priced[level - 1] + 0.01, value);
      }
      return priced;
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
    /** Полёт окончен: шар лопнул или долетел до вершины. Выигрыш мог быть зафиксирован раньше. */
    public volatile boolean flightOver = false;
    public volatile PuzzleAward reward;
    /** Порядковый номер выигрыша игрока; 0 — раунд проигран. */
    public volatile int winNumber = 0;
    /**
     * Персональное предложение «Закрепи успех» уже было принято для этого
     * раунда.  Поле находится на самом раунде, чтобы две параллельные
     * попытки принять предложение можно было сериализовать одним монитором
     * раунда (и не выдать билеты дважды).
     */
    public volatile boolean offerAccepted = false;
    /** Guard against duplicate account points settlement at flight end. */
    public volatile boolean pointsSettled = false;
    /** True while end-of-flight account/reward effects are being applied. */
    public volatile boolean settling = false;
    /** A completed reward attempt may legitimately produce no fragment (null). */
    public volatile boolean rewardSettled = false;
    /** Guards the per-player win counter when a failed settlement is retried. */
    public volatile boolean winSettled = false;
    /** All required end-of-flight side effects have completed successfully. */
    public volatile boolean settlementComplete = false;

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
    public boolean finished() { return flightOver; }
    public boolean cashedOut() { return cashedOutMultiplier != null; }
    public long profit() { return payout - betAmount; }

    public boolean hasBooster() { return boosterLevel > 0 && boosterMultiplier > 1; }

    /** Коэффициент уровня с учётом сработавшего бустера. */
    public double multiplierAt(int level) {
      double value = ladder[Math.max(0, Math.min(level, levels()))];
      return boosterActivated && level >= boosterLevel ? Math.round(value * boosterMultiplier * 100.0) / 100.0 : value;
    }

    public double nextMultiplier() { return multiplierAt(Math.min(level + 1, levels())); }

    /**
     * Коэффициент, на котором шар лопнул; если долетел до вершины —
     * максимальный коэффициент темы.  Если бустер уже активировался до
     * точки crash, итоговый коэффициент обязан отражать его множитель.
     */
    public double crashMultiplier() { return multiplierAt(Math.min(popLevel, levels())); }

    /** Лучшая выплата, которую можно было забрать в этом раунде. */
    public long potentialMax() {
      int best = Math.min(popLevel - 1, levels());
      return best < 1 ? 0 : Math.max(1, Math.round(betAmount * multiplierBefore(best)));
    }

    /** Коэффициент уровня так, как если бы бустер успел сработать до него. */
    private double multiplierBefore(int level) {
      double value = ladder[level];
      return hasBooster() && level >= boosterLevel ? Math.round(value * boosterMultiplier * 100.0) / 100.0 : value;
    }
  }
}
