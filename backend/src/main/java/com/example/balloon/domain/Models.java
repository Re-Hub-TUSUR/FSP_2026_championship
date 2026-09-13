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

  /** Первый подъём без риска: на старте, при ×1.00, шар никогда не лопается. */
  public static final int SAFE_LEVELS = 1;
  /**
   * Забрать выигрыш можно с первого уровня, до которого шар долетел с риском.
   * Фиксация на безрисковом уровне давала бы гарантированную прибыль при коэффициенте выше единицы.
   */
  public static final int MIN_CASHOUT_LEVEL = SAFE_LEVELS + 1;
  /**
   * Бустер выпадает только начиная с этого уровня. На первых уровнях коэффициенты почти равны единице:
   * скидка на бустер упиралась бы в минимальный шаг лестницы и поднимала бы возврат игроку выше 100%.
   */
  public static final int FIRST_BOOSTER_LEVEL = MIN_CASHOUT_LEVEL + 1;

  public enum Theme { RED, GREEN }
  /** REFUNDED — полёт прерван перезапуском сервера до cashout: раунд отменён, ставка возвращена игроку. */
  public enum RoundState { CREATED, STARTED, CASHED_OUT, CRASHED, SETTLED, REFUNDED }

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

    /**
     * Порог выигрыша для «Закрепи успех» по умолчанию — минимальная ставка: предложение приходит
     * раз в OFFER_EVERY_WINS выигрышей при любой ставке. Администратор может поднять порог в панели.
     */
    public static final long DEFAULT_MIN_WIN_AMOUNT = 10;

    /**
     * Параметры по умолчанию. Первый подъём без риска, дальше шанс взрыва растёт экспоненциально:
     * на втором уровне 17,1% (красный) и 12,5% (зелёный). У обеих тем коэффициент переходит ×2 на пятом
     * уровне; красный рискованнее и доходит до ×159.75, зелёный спокойнее и доходит до ×23.54, зато
     * долетает до вершины почти в 7 раз чаще.
     */
    public static Config defaults(Theme theme) {
      int levels = theme == Theme.RED ? 12 : 9;
      return new Config(theme, "balloon", "Воздушный шар", "CRASH", true,
          levels, new long[]{10, 20, 50, 100}, new double[]{1, 2, 3, 4}, defaultBoosterWeights(levels),
          10, 20, 15,
          theme == Theme.RED ? 0.15 : 0.10, theme == Theme.RED ? 1.14 : 1.25, 0.60, 0.96,
          new double[0], DEFAULT_MIN_WIN_AMOUNT, 10, 1);
    }

    public static double[] uniformWeights(int levels) {
      double[] weights = new double[Math.max(1, levels)];
      Arrays.fill(weights, 1.0 / weights.length);
      return weights;
    }

    /** Веса по умолчанию: бустер равновероятно выпадает на уровнях начиная с FIRST_BOOSTER_LEVEL. */
    public static double[] defaultBoosterWeights(int levels) {
      int first = FIRST_BOOSTER_LEVEL - 1;
      if (levels <= first) return uniformWeights(levels);
      double[] weights = new double[levels];
      Arrays.fill(weights, first, levels, 1.0 / (levels - first));
      return weights;
    }

    /**
     * Шансы прошлых версий по умолчанию, {красный, зелёный} × {baseHazard, hazardGrowth, maxHazard}. В первой шар
     * лопался до первого уровня в 12% и 15,5% раундов, во второй коэффициент доходил до ×2 только к 6–7-му уровню.
     */
    private static final double[][][] PREVIOUS_DEFAULT_MATH = {
        {{0.12, 1.115, 0.60}, {0.155, 1.12, 0.60}},
        {{0.07, 1.20, 0.60}, {0.05, 1.35, 0.60}}};
    /** Порог «Закрепи успех» прошлых версий: ставки 10 и 20 ★ до него почти не дотягивали, и предложение не приходило. */
    static final long PREVIOUS_MIN_WIN_AMOUNT = 50;

    /** Сохранённая тема использует нетронутые шансы одной из прошлых версий, а не настройки администратора. */
    public boolean usesLegacyDefaultMath() {
      // Ручная лестница подобрана под старые шансы: их замена изменила бы возврат игроку.
      if (hasCustomLadder()) return false;
      for (double[][] version : PREVIOUS_DEFAULT_MATH) {
        double[] legacy = version[theme == Theme.RED ? 0 : 1];
        if (Math.abs(baseHazard - legacy[0]) < 1e-9
            && Math.abs(hazardGrowth - legacy[1]) < 1e-9
            && Math.abs(maxHazard - legacy[2]) < 1e-9) return true;
      }
      return false;
    }

    /** Порог апсейла остался значением прошлых версий по умолчанию. */
    public boolean usesLegacyMinWinAmount() { return minWinAmount == PREVIOUS_MIN_WIN_AMOUNT; }

    public boolean needsDefaultsMigration() { return usesLegacyDefaultMath() || usesLegacyMinWinAmount(); }

    /**
     * Переводит нетронутые значения прошлых версий на текущие: шансы и порог апсейла обновляются независимо друг
     * от друга, а ставки, бустеры, очки и прочие настройки администратора сохраняются.
     */
    public Config withCurrentDefaults() {
      Config fresh = defaults(theme);
      boolean math = usesLegacyDefaultMath();
      double[] probabilities = normalizedWeights();
      double[] uniform = uniformWeights(levels);
      boolean untouchedWeights = true;
      for (int i = 0; i < levels; i++) untouchedWeights &= Math.abs(probabilities[i] - uniform[i]) < 1e-9;
      return new Config(theme, gameId, gameName, gameType, active, levels, bets, boosters,
          math && untouchedWeights ? defaultBoosterWeights(levels) : boosterWeights,
          pointsPerLevel, pointsCashoutBonus, pointsBoosterBonus,
          math ? fresh.baseHazard() : baseHazard, math ? fresh.hazardGrowth() : hazardGrowth,
          math ? fresh.maxHazard() : maxHazard, rtp,
          ladderOverride, usesLegacyMinWinAmount() ? fresh.minWinAmount() : minWinAmount, popupTimeout, version + 1);
    }

    /** Шанс взрыва на уровне. Первый подъём без риска, дальше давление в шаре растёт экспоненциально. */
    public double hazardAt(int level) {
      if (level <= SAFE_LEVELS) return 0;
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
      // Уровни раньше FIRST_BOOSTER_LEVEL исключаются всегда — даже если вероятность для них задал администратор.
      double[] weights = normalizedWeights();
      int first = Math.min(FIRST_BOOSTER_LEVEL - 1, levels - 1);
      double eligible = 0;
      for (int i = first; i < levels; i++) eligible += weights[i];
      double[] probabilities = new double[levels];
      if (eligible <= 0) {
        Arrays.fill(probabilities, first, levels, 1.0 / (levels - first));
        return probabilities;
      }
      for (int i = first; i < levels; i++) probabilities[i] = weights[i] / eligible;
      return probabilities;
    }

    /** Веса администратора, нормированные к единице без правил выпадения; пустые или неверные — равномерные. */
    private double[] normalizedWeights() {
      if (boosterWeights == null || boosterWeights.length != levels) return uniformWeights(levels);
      double sum = 0;
      for (double weight : boosterWeights) sum += Math.max(0, weight);
      if (sum <= 0) return uniformWeights(levels);
      double[] weights = new double[levels];
      for (int i = 0; i < levels; i++) weights[i] = Math.max(0, boosterWeights[i]) / sum;
      return weights;
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
    public final Instant startedAt;

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
     * За этот выигрыш положено предложение «Закрепи успех». Решение принимается при начислении выплаты
     * атомарно со счётчиком выигрышей, поэтому экран результата застаёт его готовым.
     */
    public volatile boolean offerDue = false;
    /** Первый показ предложения игроку: от него отсчитывается popupTimeout. */
    public volatile Instant offerShownAt;
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
      this(id, userId, config, betAmount, boosterMultiplier, seed, commitment, popLevel, boosterLevel, ladder, Instant.now());
    }

    /** Раунд, восстановленный из базы, сохраняет время старта исходного раунда. */
    public Round(String id, String userId, Config config, long betAmount, double boosterMultiplier,
                 String seed, String commitment, int popLevel, int boosterLevel, double[] ladder, Instant startedAt) {
      this.startedAt = startedAt;
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
      return best < MIN_CASHOUT_LEVEL ? 0 : Math.max(1, Math.round(betAmount * multiplierBefore(best)));
    }

    /** Коэффициент уровня так, как если бы бустер успел сработать до него. */
    private double multiplierBefore(int level) {
      double value = ladder[level];
      return hasBooster() && level >= boosterLevel ? Math.round(value * boosterMultiplier * 100.0) / 100.0 : value;
    }
  }
}
