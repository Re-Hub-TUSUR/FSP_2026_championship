package com.example.balloon.application;

import com.example.balloon.domain.Models.*;
import com.example.balloon.auth.AccountService;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class GameService {
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

  private final Object wallet = new Object();
  private long balance = START_BALANCE;

  public GameService(AccountService accounts) {
    this.accounts = accounts;
    configs.put(Theme.RED, Config.defaults(Theme.RED));
    configs.put(Theme.GREEN, Config.defaults(Theme.GREEN));
    scheduler = Executors.newScheduledThreadPool(4, r -> {
      Thread t = new Thread(r, "round-ticker");
      t.setDaemon(true);
      return t;
    });
  }

  public Config config(Theme theme) { return configs.get(theme); }

  public long balance() { synchronized (wallet) { try { return accounts.byUsername("demo").getBalance(); } catch (Exception ignored) { return balance; } } }

  public Round start(String user, Theme theme, String betOption) {
    Config config = Optional.ofNullable(config(theme)).orElseThrow(() -> new IllegalArgumentException("Неизвестная тема"));
    int index = optionIndex(betOption, config.bets().length);
    long bet = config.bets()[index];
    long balanceAfterBet;
    try { balanceAfterBet = accounts.debit(user, bet).getBalance(); }
    catch (NoSuchElementException e) { balanceAfterBet = debit(bet); }

    String id = UUID.randomUUID().toString();
    String seed = randomSeed();
    String commitment = sha256(seed + ":" + id + ":" + config.version());
    Round round = new Round(id, user, config, bet,
        config.boosters()[Math.min(index, config.boosters().length - 1)],
        seed, commitment, popLevel(seed, id, config), 1 + random.nextInt(config.levels()), config.ladder());
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

  public Round cashout(String id) {
    Round round = get(id);
    synchronized (round) {
      if (round.state == RoundState.CASHED_OUT) return round;
      if (round.state != RoundState.STARTED) throw new IllegalStateException("Раунд уже завершён");
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

  public Config updateConfig(Theme theme, Config request) {
    Config current = config(theme);
    if (request == null) throw new IllegalArgumentException("Конфигурация обязательна");
    int levels = request.levels() > 0 ? request.levels() : current.levels();
    long[] bets = request.bets() == null || request.bets().length == 0 ? current.bets() : request.bets();
    double[] boosters = request.boosters() == null || request.boosters().length == 0 ? current.boosters() : request.boosters();
    if (levels < 1 || levels > 30 || bets.length != 4 || boosters.length != 4)
      throw new IllegalArgumentException("Некорректные уровни, ставки или бустеры");
    double baseHazard = clamp(request.baseHazard() > 0 ? request.baseHazard() : current.baseHazard(), 0.01, 0.5);
    double hazardGrowth = clamp(request.hazardGrowth() > 0 ? request.hazardGrowth() : current.hazardGrowth(), 1.0, 2.0);
    double maxHazard = clamp(request.maxHazard() > 0 ? request.maxHazard() : current.maxHazard(), baseHazard, 0.95);
    double rtp = clamp(request.rtp() > 0 ? request.rtp() : current.rtp(), 0.5, 1.0);
    Config updated = new Config(theme, levels, bets, boosters,
        Math.max(0, request.pointsPerLevel()), Math.max(0, request.pointsCashoutBonus()), Math.max(0, request.pointsBoosterBonus()),
        baseHazard, hazardGrowth, maxHazard, rtp, current.version() + 1);
    configs.put(theme, updated);
    return updated;
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
      if (round.state != RoundState.STARTED) { stopTicker(round.id); return; }
      int next = round.level + 1;
      if (next >= round.popLevel) { finish(round, RoundState.CRASHED); return; }

      round.level = next;
      round.multiplier = round.ladder[next];
      round.points += round.config.pointsPerLevel();
      if (next == round.boosterLevel && !round.boosterActivated) {
        round.boosterActivated = true;
        round.points += (int) Math.round(round.config.pointsBoosterBonus() * round.boosterMultiplier);
      }
      // Шар долетел до вершины — выигрыш начисляется автоматически.
      if (next >= round.levels()) payout(round, RoundState.SETTLED);
    }
  }

  private void payout(Round round, RoundState state) {
    round.cashedOutMultiplier = round.multiplier;
    round.payout = Math.max(1, Math.round(round.betAmount * round.multiplier));
    round.points += round.config.pointsCashoutBonus();
    try { accounts.credit(round.userId, round.payout); accounts.addPoints(round.userId, round.points); }
    catch (NoSuchElementException e) { credit(round.payout); }
    finish(round, state);
  }

  private void finish(Round round, RoundState state) {
    round.state = state;
    round.endedAt = Instant.now();
    if (state == RoundState.CRASHED) {
      try { accounts.addPoints(round.userId, round.points); } catch (Exception ignored) { /* demo fallback */ }
    }
    try { round.balanceAfter = accounts.byUsername(round.userId).getBalance(); }
    catch (Exception ignored) { round.balanceAfter = balance(); }
    stopTicker(round.id);
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

  private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

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
