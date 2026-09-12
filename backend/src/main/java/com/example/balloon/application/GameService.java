package com.example.balloon.application;

import com.example.balloon.domain.Models.*;
import static com.example.balloon.domain.Models.MAX_MULTIPLIER;
import com.example.balloon.auth.AccountService;
import com.example.balloon.config.GameConfigEntity;
import com.example.balloon.config.GameConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class GameService implements CommandLineRunner {
  private static final Logger log = LoggerFactory.getLogger(GameService.class);
  private static final long TICK_MS = 1000;
  private static final int HISTORY_LIMIT = 200;
  private static final long START_BALANCE = 1000;

  private final Map<String, Round> rounds = new ConcurrentHashMap<>();
  private final Queue<String> roundOrder = new ConcurrentLinkedQueue<>();
  private final Map<String, Round> idempotent = Collections.synchronizedMap(new LinkedHashMap<>() {
    @Override protected boolean removeEldestEntry(Map.Entry<String, Round> eldest) { return size() > HISTORY_LIMIT; }
  });
  private final Map<Theme, Config> configs = new ConcurrentHashMap<>();
  private final Map<String, ScheduledFuture<?>> tickers = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;
  private final SecureRandom random = new SecureRandom();
  private final AccountService accounts;
  private final GameConfigRepository configRepository;
  private final ObjectMapper mapper;

  private final Object wallet = new Object();
  private long balance = START_BALANCE;

  public GameService(AccountService accounts, GameConfigRepository configRepository, ObjectMapper mapper) {
    this.accounts = accounts;
    this.configRepository = configRepository;
    this.mapper = mapper;
    configs.put(Theme.RED, Config.defaults(Theme.RED));
    configs.put(Theme.GREEN, Config.defaults(Theme.GREEN));
    scheduler = Executors.newScheduledThreadPool(4, r -> {
      Thread t = new Thread(r, "round-ticker");
      t.setDaemon(true);
      return t;
    });
  }

  /** Поднимает опубликованные администратором настройки: без этого правки терялись бы при перезапуске. */
  @Override
  public void run(String... args) {
    for (GameConfigEntity entity : configRepository.findAll()) {
      try {
        Config stored = mapper.readValue(entity.getPayload(), Config.class);
        if (stored.theme() != null) configs.put(stored.theme(), stored);
      } catch (Exception ignored) {
        // Повреждённая запись не должна ронять старт — тема останется на значениях по умолчанию.
      }
    }
  }

  public Config config(Theme theme) { return configs.get(theme); }

  public long balance() { synchronized (wallet) { try { return accounts.byUsername("demo").getBalance(); } catch (Exception ignored) { return balance; } } }

  public Round start(String user, Theme theme, String betOption) {
    Config config = Optional.ofNullable(config(theme)).orElseThrow(() -> new IllegalArgumentException("Неизвестная тема"));
    if (!config.active()) throw new IllegalStateException("Тема временно отключена администратором");
    int index = optionIndex(betOption, config.bets().length);
    long bet = config.bets()[index];
    long balanceAfterBet;
    try { balanceAfterBet = accounts.debit(user, bet).getBalance(); }
    catch (NoSuchElementException e) { balanceAfterBet = debit(bet); }

    String id = UUID.randomUUID().toString();
    String seed = randomSeed();
    String commitment = sha256(seed + ":" + id + ":" + config.version());
    double booster = config.boosters()[Math.min(index, config.boosters().length - 1)];
    Round round = new Round(id, user, config, bet, booster,
        seed, commitment, popLevel(seed, id, config), pickBoosterLevel(config, booster), config.ladderForBooster(booster));
    round.balanceAfter = balanceAfterBet;

    remember(round);
    tickers.put(id, scheduler.scheduleAtFixedRate(() -> tick(round), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS));
    return round;
  }

  public Round startIdempotent(String user, Theme theme, String betOption, String key) {
    String mapKey = user + ":" + key;
    if (key != null && !key.isBlank()) {
      Round previous = idempotent.get(mapKey);
      if (previous != null) return previous;
    }
    Round round = start(user, theme, betOption);
    if (key != null && !key.isBlank()) idempotent.put(mapKey, round);
    return round;
  }

  public Round get(String id) {
    Round round = rounds.get(id);
    if (round == null) throw new NoSuchElementException("Раунд не найден: " + id);
    return round;
  }

  /**
   * Фиксирует выигрыш, но не останавливает полёт: по п. 1.3 шар продолжает лететь
   * до точки взрыва, и только тогда игроку открывается экран результата.
   */
  public Round cashout(String id) {
    Round round = get(id);
    synchronized (round) {
      if (round.cashedOut()) return round;
      if (round.flightOver) throw new IllegalStateException("Раунд уже завершён");
      if (round.level < 1) throw new IllegalStateException("Забрать выигрыш можно после первого уровня");
      payout(round, RoundState.CASHED_OUT);
      return round;
    }
  }

  public List<Round> history() {
    return rounds.values().stream()
        .filter(Round::finished)
        .sorted(Comparator.comparing((Round r) -> r.startedAt).reversed())
        .toList();
  }

  /** Live authoritative snapshot used by leaderboard polling. */
  public List<Round> allRounds() { return List.copyOf(rounds.values()); }

  /** Применяет настройки администратора: сначала проверка допустимости, затем запись в базу и в память. */
  public Config updateConfig(Theme theme, Config request, String actor) {
    if (request == null) throw new IllegalArgumentException("Конфигурация обязательна");
    Config updated = validate(theme, request, config(theme));
    persist(updated, actor);
    configs.put(theme, updated);
    return updated;
  }

  /** Возвращает тему к значениям по умолчанию. */
  public Config resetConfig(Theme theme, String actor) {
    Config defaults = Config.defaults(theme);
    Config restored = new Config(theme, defaults.gameId(), defaults.gameName(), defaults.gameType(), defaults.active(),
        defaults.levels(), defaults.bets(), defaults.boosters(), defaults.boosterWeights(),
        defaults.pointsPerLevel(), defaults.pointsCashoutBonus(), defaults.pointsBoosterBonus(),
        defaults.baseHazard(), defaults.hazardGrowth(), defaults.maxHazard(), defaults.rtp(),
        new double[0], config(theme).version() + 1);
    persist(restored, actor);
    configs.put(theme, restored);
    return restored;
  }

  private Config validate(Theme theme, Config request, Config current) {
    String gameId = blankOr(request.gameId(), current.gameId());
    String gameName = blankOr(request.gameName(), current.gameName());
    String gameType = blankOr(request.gameType(), current.gameType());

    int levels = request.levels();
    if (levels < 3 || levels > 30) throw new IllegalArgumentException("Количество уровней должно быть от 3 до 30");

    long[] bets = request.bets();
    if (bets == null || bets.length != 4) throw new IllegalArgumentException("Нужно ровно 4 варианта ставки");
    for (long bet : bets) if (bet < 1) throw new IllegalArgumentException("Ставка должна быть больше нуля");

    double[] boosters = request.boosters();
    if (boosters == null || boosters.length != 4) throw new IllegalArgumentException("Нужно ровно 4 множителя бустера");
    for (double booster : boosters)
      if (booster < 1 || booster > 100) throw new IllegalArgumentException("Множитель бустера должен быть от 1 до 100");

    if (request.pointsPerLevel() < 0 || request.pointsCashoutBonus() < 0 || request.pointsBoosterBonus() < 0)
      throw new IllegalArgumentException("Начисление очков не может быть отрицательным");

    double baseHazard = request.baseHazard();
    if (baseHazard < 0.001 || baseHazard > 0.5)
      throw new IllegalArgumentException("Базовый шанс взрыва должен быть от 0.001 до 0.5");
    double hazardGrowth = request.hazardGrowth();
    if (hazardGrowth < 1 || hazardGrowth > 2)
      throw new IllegalArgumentException("Темп роста шанса взрыва должен быть от 1.0 до 2.0");
    double maxHazard = request.maxHazard();
    if (maxHazard < baseHazard || maxHazard > 0.95)
      throw new IllegalArgumentException("Потолок шанса взрыва должен быть между базовым шансом и 0.95");
    double rtp = request.rtp();
    if (rtp < 0.5 || rtp > 1)
      throw new IllegalArgumentException("Возврат игроку (RTP) должен быть от 0.5 до 1.0");

    double[] weights = request.boosterWeights();
    if (weights == null || weights.length != levels) {
      weights = Config.uniformWeights(levels);
    } else {
      double sum = 0;
      for (double weight : weights) {
        if (weight < 0) throw new IllegalArgumentException("Вероятность бустера не может быть отрицательной");
        sum += weight;
      }
      if (sum <= 0) throw new IllegalArgumentException("Хотя бы у одного уровня вероятность бустера должна быть больше нуля");
    }

    double[] ladder = normalizeLadder(request.ladderOverride(), levels);

    return new Config(theme, gameId, gameName, gameType, request.active(),
        levels, bets, boosters, weights,
        request.pointsPerLevel(), request.pointsCashoutBonus(), request.pointsBoosterBonus(),
        baseHazard, hazardGrowth, maxHazard, rtp, ladder, current.version() + 1);
  }

  /** Ручная лестница: пустая — считаем по формуле, иначе значения должны строго расти. */
  private double[] normalizeLadder(double[] requested, int levels) {
    if (requested == null || requested.length == 0) return new double[0];
    if (requested.length != levels)
      throw new IllegalArgumentException("Коэффициентов должно быть ровно " + levels + " — по одному на уровень");
    double[] ladder = new double[levels];
    double previous = 1.0;
    for (int i = 0; i < levels; i++) {
      double value = Math.round(requested[i] * 100.0) / 100.0;
      if (value <= previous)
        throw new IllegalArgumentException("Коэффициент уровня " + (i + 1) + " должен быть больше предыдущего (" + previous + ")");
      if (value > MAX_MULTIPLIER)
        throw new IllegalArgumentException("Коэффициент не может превышать " + (long) MAX_MULTIPLIER);
      ladder[i] = value;
      previous = value;
    }
    return ladder;
  }

  private void persist(Config config, String actor) {
    try {
      String payload = mapper.writeValueAsString(config);
      GameConfigEntity entity = configRepository.findById(config.theme().name()).orElse(null);
      if (entity == null) configRepository.save(new GameConfigEntity(config.theme().name(), payload, actor));
      else { entity.update(payload, actor); configRepository.save(entity); }
    } catch (Exception e) {
      throw new IllegalStateException("Не удалось сохранить конфигурацию: " + e.getMessage());
    }
  }

  private static String blankOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  /**
   * Уровень бустера по заданным администратором вероятностям (line_N_loot_prob).
   * Ноль означает, что в этом раунде бустер не выпал: сильный бустер появляется реже.
   */
  private int pickBoosterLevel(Config config, double booster) {
    if (random.nextDouble() >= config.boosterAppearChance(booster)) return 0;
    double[] probabilities = config.boosterProbabilities();
    double roll = random.nextDouble();
    double accumulated = 0;
    for (int level = 1; level <= config.levels(); level++) {
      accumulated += probabilities[level - 1];
      if (roll < accumulated) return level;
    }
    return config.levels();
  }

  public Map<String, Object> proof(String id) {
    Round round = get(id);
    Map<String, Object> proof = new LinkedHashMap<>();
    proof.put("roundId", round.id);
    proof.put("commitment", "sha256:" + round.commitment);
    proof.put("configVersion", round.config.version());
    if (round.finished()) {
      proof.put("seed", round.seed);
      proof.put("popLevel", round.popLevel);
      proof.put("boosterLevel", round.boosterLevel);
      proof.put("ladder", round.ladder);
      proof.put("formula", "hazard(level) = min(maxHazard, baseHazard * hazardGrowth^(level-1)); шар лопается на первом уровне, где HMAC_SHA256(seed, roundId:level) < hazard(level)");
    }
    return proof;
  }

  private void tick(Round round) {
    synchronized (round) {
      if (round.flightOver) { stopTicker(round.id); return; }
      int next = round.level + 1;

      if (next >= round.popLevel) {
        // Шар лопнул. Если выигрыш уже забрали, он остаётся за игроком.
        if (round.cashedOut()) endFlight(round);
        else finish(round, RoundState.CRASHED);
        return;
      }

      round.level = next;
      // После cashout бустер не активируется и очки не начисляются — полёт идёт только для наглядности.
      if (!round.cashedOut()) {
        if (next == round.boosterLevel && !round.boosterActivated) {
          round.boosterActivated = true;
          round.points += (int) Math.round(round.config.pointsBoosterBonus() * round.boosterMultiplier);
        }
        round.points += round.config.pointsPerLevel();
      }
      round.multiplier = round.multiplierAt(next);

      // Шар долетел до вершины.
      if (next >= round.levels()) {
        if (round.cashedOut()) endFlight(round);
        else payout(round, RoundState.SETTLED);
      }
    }
  }

  private void payout(Round round, RoundState state) {
    round.cashedOutMultiplier = round.multiplier;
    round.payout = Math.max(1, Math.round(round.betAmount * round.multiplier));
    round.points += round.config.pointsCashoutBonus();
    try { accounts.credit(round.userId, round.payout); accounts.addPoints(round.userId, round.points); }
    catch (NoSuchElementException e) { credit(round.payout); }
    round.state = state;
    round.balanceAfter = playerBalance(round);
    // Cashout только фиксирует выплату: полёт останавливается отдельно, когда шар лопнет.
    if (state != RoundState.CASHED_OUT) endFlight(round);
  }

  private void finish(Round round, RoundState state) {
    round.state = state;
    if (state == RoundState.CRASHED) {
      try { accounts.addPoints(round.userId, round.points); } catch (Exception ignored) { /* демо-режим без аккаунтов */ }
    }
    endFlight(round);
  }

  private void endFlight(Round round) {
    round.flightOver = true;
    round.endedAt = Instant.now();
    // Награда выдаётся по итогам раунда независимо от выигрыша или проигрыша.
    try { round.reward = accounts.awardPuzzleFragment(round.userId); }
    catch (Exception e) {
      // Демо-режим может работать без аккаунта, но production-сбой не должен исчезать бесследно.
      log.warn("Не удалось выдать ежедневный фрагмент пазла для раунда {} пользователя {}: {}",
          round.id, round.userId, e.getMessage());
    }
    round.balanceAfter = playerBalance(round);
    stopTicker(round.id);
  }

  private long playerBalance(Round round) {
    try { return accounts.byUsername(round.userId).getBalance(); }
    catch (Exception ignored) { return balance(); }
  }

  private void stopTicker(String id) {
    ScheduledFuture<?> ticker = tickers.remove(id);
    if (ticker != null) ticker.cancel(false);
  }

  private void remember(Round round) {
    rounds.put(round.id, round);
    roundOrder.add(round.id);
    while (roundOrder.size() > HISTORY_LIMIT) {
      String oldest = roundOrder.poll();
      if (oldest != null) rounds.remove(oldest);
    }
  }

  private long debit(long amount) {
    synchronized (wallet) {
      if (balance < amount) throw new IllegalStateException("Недостаточно баланса");
      balance -= amount;
      return balance;
    }
  }

  private void credit(long amount) {
    synchronized (wallet) { balance += amount; }
  }

  /** Первый уровень, на котором шар лопается; levels+1 означает, что шар долетел до вершины. */
  private int popLevel(String seed, String id, Config config) {
    for (int level = 1; level <= config.levels(); level++) {
      if (hmacUnit(seed, id + ":" + level) < config.hazardAt(level)) return level;
    }
    return config.levels() + 1;
  }

  private int optionIndex(String option, int count) {
    if (option == null) return Math.min(1, count - 1);
    try {
      int n = Integer.parseInt(option.replaceAll("\\D", ""));
      return Math.max(0, Math.min(count - 1, n - 1));
    } catch (NumberFormatException e) {
      return Math.min(1, count - 1);
    }
  }

  private static double hmacUnit(String seed, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(seed.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
      long value = 0;
      for (int i = 0; i < 8; i++) value = (value << 8) | (digest[i] & 0xffL);
      return (value >>> 1) / (double) Long.MAX_VALUE;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String randomSeed() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(64);
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
