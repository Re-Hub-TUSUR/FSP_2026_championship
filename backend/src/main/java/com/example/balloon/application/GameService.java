package com.example.balloon.application;

import com.example.balloon.domain.Models.*;
import static com.example.balloon.domain.Models.FIRST_BOOSTER_LEVEL;
import static com.example.balloon.domain.Models.MAX_MULTIPLIER;
import static com.example.balloon.domain.Models.MIN_CASHOUT_LEVEL;
import com.example.balloon.auth.AccountService;
import com.example.balloon.config.GameConfigEntity;
import com.example.balloon.config.GameConfigRepository;
import com.example.balloon.rounds.RoundEntity;
import com.example.balloon.rounds.RoundStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.function.Consumer;

@Service
public class GameService implements CommandLineRunner {
  private static final Logger log = LoggerFactory.getLogger(GameService.class);
  /** Длительность уровня: игрок должен успеть увидеть новый коэффициент и нажать «Забрать». */
  private static final long TICK_MS = 1500;
  /** Сколько раундов держится в памяти для опроса экрана полёта; история целиком хранится в базе. */
  private static final int HISTORY_LIMIT = 200;

  private final Map<String, Round> rounds = new ConcurrentHashMap<>();
  private final Queue<String> roundOrder = new ConcurrentLinkedQueue<>();
  /**
   * Guarded by {@link #idempotencyLock} and retained as long as its round is
   * retained. A plain get/put pair could debit twice on concurrent retries;
   * a fixed-size LRU cache could evict an active round's key during a burst.
   * Keys are therefore pruned together with evicted finished rounds; after
   * that the key is still found in the database together with its round.
   */
  private final Map<String, IdempotencyRecord> idempotent = new HashMap<>();
  private final Object idempotencyLock = new Object();
  private final Map<Theme, Config> configs = new ConcurrentHashMap<>();
  private final Map<String, ScheduledFuture<?>> tickers = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;
  private final SecureRandom random = new SecureRandom();
  private final AccountService accounts;
  private final GameConfigRepository configRepository;
  private final ObjectMapper mapper;
  private final RoundStore store;
  private final TransactionTemplate transactions;
  private final long tickMs;

  private record IdempotencyRecord(Round round, Theme theme, String betOption) {}
  private record PayoutOutcome(long balanceAfter, AccountService.WinRecord win) {}

  public GameService(AccountService accounts, GameConfigRepository configRepository, ObjectMapper mapper,
                     RoundStore store, PlatformTransactionManager transactionManager,
                     @Value("${game.tick-ms:" + TICK_MS + "}") long tickMs) {
    this.accounts = accounts;
    this.configRepository = configRepository;
    this.mapper = mapper;
    this.store = store;
    this.transactions = new TransactionTemplate(transactionManager);
    this.tickMs = tickMs;
    configs.put(Theme.RED, Config.defaults(Theme.RED));
    configs.put(Theme.GREEN, Config.defaults(Theme.GREEN));
    scheduler = Executors.newScheduledThreadPool(4, r -> {
      Thread t = new Thread(r, "round-ticker");
      t.setDaemon(true);
      return t;
    });
  }

  @Override
  public void run(String... args) {
    loadPublishedConfigs();
    recoverUnsettledRounds();
  }

  /** Поднимает опубликованные администратором настройки: без этого правки терялись бы при перезапуске. */
  private void loadPublishedConfigs() {
    for (GameConfigEntity entity : configRepository.findAll()) {
      try {
        Theme theme = Theme.valueOf(entity.getTheme());
        // Сохранённые настройки накладываются поверх значений по умолчанию: поля,
        // появившиеся после публикации конфига, берутся из defaults, а не обнуляются.
        ObjectNode merged = mapper.valueToTree(Config.defaults(theme));
        merged.setAll((ObjectNode) mapper.readTree(entity.getPayload()));
        Config loaded = mapper.treeToValue(merged, Config.class);
        int requiredLevels = theme == Theme.RED ? 12 : 9;
        if (loaded.levels() != requiredLevels) {
          log.warn("Игнорируется конфигурация {} с {} уровнями: для темы требуется {}",
              theme, loaded.levels(), requiredLevels);
          continue;
        }
        if (loaded.needsDefaultsMigration()) {
          // Шансы или порог апсейла ни разу не настраивались: переводим тему на значения текущей версии.
          loaded = loaded.withCurrentDefaults();
          persist(loaded, "system");
          log.info("Тема {}: нетронутые значения прошлых версий обновлены до текущих значений по умолчанию", theme);
        }
        configs.put(theme, loaded);
      } catch (Exception ignored) {
        // Повреждённая запись не должна ронять старт — тема останется на значениях по умолчанию.
      }
    }
  }

  /**
   * Подводит итоги раундов, прерванных перезапуском. Выигрыш, зафиксированный до остановки, остаётся
   * за игроком: полёт доводится до точки взрыва по сиду раунда. Раунд без cashout отменяется с возвратом
   * ставки — пока сервер был недоступен, игрок не мог забрать выигрыш.
   */
  private void recoverUnsettledRounds() {
    List<Round> unsettled;
    try {
      unsettled = store.unsettled();
    } catch (Exception e) {
      log.error("Не удалось загрузить раунды с неподведёнными итогами: {}", e.getMessage(), e);
      return;
    }
    for (Round round : unsettled) {
      try {
        synchronized (round) {
          if (!round.flightOver) {
            if (round.cashedOut()) finishInterruptedFlight(round);
            else refund(round, "полёт прерван перезапуском сервера");
          } else {
            endFlight(round);
          }
        }
        if (!round.settlementComplete) {
          // Итоги не записались, например база ещё недоступна: такт повторит их, как для обычного раунда.
          remember(round);
          tickers.put(round.id, scheduler.scheduleAtFixedRate(() -> tick(round), tickMs, tickMs, TimeUnit.MILLISECONDS));
        }
        log.info("Раунд {} игрока {} после перезапуска: {}", round.id, round.userId, round.state);
      } catch (Exception e) {
        log.error("Не удалось подвести итоги раунда {} после перезапуска: {}", round.id, e.getMessage(), e);
      }
    }
  }

  /** Доводит полёт после cashout до точки взрыва: уровни, пропущенные за время перезапуска, приносят свои очки. */
  private void finishInterruptedFlight(Round round) {
    int finalLevel = Math.min(round.popLevel - 1, round.levels());
    if (finalLevel > round.level) {
      round.points = Math.addExact(round.points, Math.multiplyExact(finalLevel - round.level, round.config.pointsPerLevel()));
      round.level = finalLevel;
      round.multiplier = round.multiplierAt(finalLevel);
    }
    endFlight(round);
  }

  /** Отменяет раунд без cashout: ставка возвращается одной транзакцией с записью раунда и журналом. */
  private void refund(Round round, String reason) {
    Instant endedAt = Instant.now();
    long balanceAfter = transactions.execute(status -> {
      long balance = accounts.credit(round.userId, round.betAmount).getBalance();
      store.update(round.id, entity -> entity.refund(balance, endedAt));
      accounts.audit("system", "ROUND_REFUND",
          round.userId + ": раунд " + round.id + " — " + reason + ", ставка " + round.betAmount + " возвращена");
      return balance;
    });
    round.state = RoundState.REFUNDED;
    round.payout = round.betAmount;
    round.cashedOutMultiplier = null;
    round.points = 0;
    round.multiplier = 1.0;
    round.balanceAfter = balanceAfter;
    round.endedAt = endedAt;
    round.flightOver = true;
    round.pointsSettled = true;
    round.rewardSettled = true;
    round.winSettled = true;
    round.settlementComplete = true;
  }

  /** Останавливает такты до закрытия базы: незавершённые раунды подводятся при следующем старте. */
  @PreDestroy
  public void shutdown() {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public Config config(Theme theme) { return configs.get(theme); }

  /**
   * Compatibility accessor for the demo account. Gameplay never uses a
   * process-local wallet; this value always comes from the persisted account.
   */
  public long balance() { return accounts.byUsername("demo").getBalance(); }

  public Round start(String user, Theme theme, String betOption) {
    return start(user, theme, betOption, null);
  }

  private Round start(String user, Theme theme, String betOption, String idempotencyKey) {
    Config config = Optional.ofNullable(config(theme)).orElseThrow(() -> new IllegalArgumentException("Неизвестная тема"));
    if (!config.active()) throw new IllegalStateException("Тема временно отключена администратором");
    int index = optionIndex(betOption, theme, config.bets().length);
    long bet = config.bets()[index];

    // Construct all fallible in-memory data before debiting the account.
    String id = UUID.randomUUID().toString();
    String seed = randomSeed();
    String commitment = sha256(seed + ":" + id + ":" + config.version());
    double booster = config.boosters()[Math.min(index, config.boosters().length - 1)];
    int popLevel = popLevel(seed, id, config);
    int boosterLevel = pickBoosterLevel(config, booster);
    double[] ladder = config.ladderForBooster(booster);
    Round round = new Round(id, user, config, bet, booster,
        seed, commitment, popLevel, boosterLevel, ladder);

    // Ставка списывается в одной транзакции с записью раунда: в базе не бывает списанной ставки без раунда.
    transactions.executeWithoutResult(status -> {
      round.balanceAfter = accounts.debit(user, bet).getBalance();
      store.insert(round, betOption, idempotencyKey);
    });

    try {
      remember(round);
      tickers.put(id, scheduler.scheduleAtFixedRate(() -> tick(round), tickMs, tickMs, TimeUnit.MILLISECONDS));
      return round;
    } catch (RuntimeException e) {
      // Do not leave a debited account behind if local round registration or
      // scheduler submission fails before the round can be played.
      rounds.remove(id, round);
      roundOrder.remove(id);
      try { refund(round, "не удалось запустить полёт"); }
      catch (Exception compensationFailure) {
        log.error("Не удалось вернуть ставку {} пользователю {} после ошибки старта", bet, user, compensationFailure);
      }
      throw e;
    }
  }

  public Round startIdempotent(String user, Theme theme, String betOption, String key) {
    String normalizedKey = normalizeIdempotencyKey(key);
    if (normalizedKey == null) return start(user, theme, betOption);

    String mapKey = user + ":" + normalizedKey;
    synchronized (idempotencyLock) {
      IdempotencyRecord previous = idempotent.get(mapKey);
      if (previous != null) {
        if (rounds.containsKey(previous.round().id)) {
          if (previous.theme() != theme || !Objects.equals(previous.betOption(), betOption)) {
            throw new IllegalArgumentException("Idempotency-Key уже использован для другого запроса");
          }
          return previous.round();
        }
        idempotent.remove(mapKey);
      }
      // Ключ хранится вместе с раундом: повтор после вытеснения из памяти или перезапуска
      // возвращает тот же раунд и не списывает ставку второй раз.
      Optional<RoundStore.StoredStart> stored = store.findStart(user, normalizedKey);
      if (stored.isPresent()) {
        Round storedRound = stored.get().round();
        if (storedRound.theme() != theme || !Objects.equals(stored.get().betOptionId(), betOption)) {
          throw new IllegalArgumentException("Idempotency-Key уже использован для другого запроса");
        }
        return rounds.getOrDefault(storedRound.id, storedRound);
      }
      Round round = start(user, theme, betOption, normalizedKey);
      idempotent.put(mapKey, new IdempotencyRecord(round, theme, betOption));
      return round;
    }
  }

  public Round get(String id) {
    Round round = rounds.get(id);
    if (round != null) return round;
    // Завершённые раунды вытесняются из памяти, но остаются в базе.
    return store.find(id).orElseThrow(() -> new NoSuchElementException("Раунд не найден: " + id));
  }

  /** Returns a round only to its owner; mismatches are deliberately hidden as 404. */
  public Round getOwned(String id, String username) {
    Round round = get(id);
    if (username == null || !round.userId.equals(username)) {
      throw new NoSuchElementException("Раунд не найден: " + id);
    }
    return round;
  }

  /**
   * Фиксирует выигрыш, но не останавливает полёт: по п. 1.3 шар продолжает лететь
   * до точки взрыва, и только тогда игроку открывается экран результата.
   */
  public Round cashout(String id) {
    Round round = get(id);
    return cashoutOwned(round);
  }

  private Round cashoutOwned(Round round) {
    synchronized (round) {
      if (round.cashedOut()) return round;
      // Летящий шар всегда в памяти: копия из базы — уже завершённый раунд или раунд, ждущий итогов после перезапуска.
      if (round.flightOver || rounds.get(round.id) != round) throw new IllegalStateException("Раунд уже завершён");
      if (round.level < MIN_CASHOUT_LEVEL) throw new IllegalStateException("Забрать выигрыш можно со второго уровня: первый подъём проходит без риска");
      payout(round, RoundState.CASHED_OUT);
      return round;
    }
  }

  public Round cashoutOwned(String id, String username) {
    return cashoutOwned(getOwned(id, username));
  }

  /** Завершённые раунды всех игроков из базы, новые первыми: общая лента и «Прошлые игры». */
  public List<Round> history(int limit) {
    return store.recentFinished(limit);
  }

  /** Завершённые раунды одного игрока из базы, новые первыми: для вкладки «Мои раунды». */
  public List<Round> historyOf(String username, int limit) {
    return store.recentFinishedOf(username, limit);
  }

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
        new double[0], defaults.minWinAmount(), defaults.popupTimeout(), config(theme).version() + 1);
    persist(restored, actor);
    configs.put(theme, restored);
    return restored;
  }

  private Config validate(Theme theme, Config request, Config current) {
    String gameId = blankOr(request.gameId(), current.gameId());
    String gameName = blankOr(request.gameName(), current.gameName());
    String gameType = blankOr(request.gameType(), current.gameType());

    int levels = request.levels();
    int requiredLevels = theme == Theme.RED ? 12 : 9;
    if (levels != requiredLevels)
      throw new IllegalArgumentException("Для темы " + theme + " должно быть ровно " + requiredLevels + " уровней");

    long[] bets = request.bets();
    if (bets == null || bets.length != 4) throw new IllegalArgumentException("Нужно ровно 4 варианта ставки");
    for (long bet : bets) if (bet < 1) throw new IllegalArgumentException("Ставка должна быть больше нуля");

    double[] boosters = request.boosters();
    if (boosters == null || boosters.length != 4) throw new IllegalArgumentException("Нужно ровно 4 множителя бустера");
    for (double booster : boosters)
      if (!Double.isFinite(booster) || booster < 1 || booster > 100)
        throw new IllegalArgumentException("Множитель бустера должен быть конечным числом от 1 до 100");

    if (request.pointsPerLevel() < 0 || request.pointsCashoutBonus() < 0 || request.pointsBoosterBonus() < 0)
      throw new IllegalArgumentException("Начисление очков не может быть отрицательным");
    try {
      long maximumBooster = (long) Math.ceil(Arrays.stream(boosters).max().orElse(1));
      long maximumRoundPoints = Math.addExact(
          Math.addExact(Math.multiplyExact((long) levels, request.pointsPerLevel()), request.pointsCashoutBonus()),
          Math.multiplyExact((long) request.pointsBoosterBonus(), maximumBooster));
      if (maximumRoundPoints > Integer.MAX_VALUE)
        throw new IllegalArgumentException("Начисление очков переполняет лимит одного раунда");
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Начисление очков переполняет лимит одного раунда");
    }

    double baseHazard = request.baseHazard();
    if (!Double.isFinite(baseHazard) || baseHazard < 0.001 || baseHazard > 0.5)
      throw new IllegalArgumentException("Базовый шанс взрыва должен быть от 0.001 до 0.5");
    double hazardGrowth = request.hazardGrowth();
    if (!Double.isFinite(hazardGrowth) || hazardGrowth < 1 || hazardGrowth > 2)
      throw new IllegalArgumentException("Темп роста шанса взрыва должен быть от 1.0 до 2.0");
    double maxHazard = request.maxHazard();
    if (!Double.isFinite(maxHazard) || maxHazard < baseHazard || maxHazard > 0.95)
      throw new IllegalArgumentException("Потолок шанса взрыва должен быть между базовым шансом и 0.95");
    double rtp = request.rtp();
    if (!Double.isFinite(rtp) || rtp < 0.5 || rtp > 1)
      throw new IllegalArgumentException("Возврат игроку (RTP) должен быть от 0.5 до 1.0");

    double[] weights = request.boosterWeights();
    if (weights == null || weights.length != levels) {
      weights = Config.uniformWeights(levels);
    } else {
      double eligible = 0;
      for (int i = 0; i < weights.length; i++) {
        double weight = weights[i];
        if (!Double.isFinite(weight) || weight < 0)
          throw new IllegalArgumentException("Вероятность бустера должна быть конечной и неотрицательной");
        if (i + 1 >= FIRST_BOOSTER_LEVEL) eligible += weight;
      }
      if (eligible <= 0)
        throw new IllegalArgumentException("Бустер выпадает начиная с " + FIRST_BOOSTER_LEVEL + "-го уровня: задайте вероятность хотя бы для одного из них");
    }

    double[] ladder = normalizeLadder(request.ladderOverride(), levels);

    if (request.minWinAmount() < 0) throw new IllegalArgumentException("Минимальный выигрыш для апсейла не может быть отрицательным");
    if (request.popupTimeout() < 3 || request.popupTimeout() > 60)
      throw new IllegalArgumentException("Таймаут всплывающего окна должен быть от 3 до 60 секунд");

    return new Config(theme, gameId, gameName, gameType, request.active(),
        levels, bets, boosters, weights,
        request.pointsPerLevel(), request.pointsCashoutBonus(), request.pointsBoosterBonus(),
        baseHazard, hazardGrowth, maxHazard, rtp, ladder,
        request.minWinAmount(), request.popupTimeout(), current.version() + 1);
  }

  /** Ручная лестница: пустая — считаем по формуле, иначе значения должны строго расти. */
  private double[] normalizeLadder(double[] requested, int levels) {
    if (requested == null || requested.length == 0) return new double[0];
    if (requested.length != levels)
      throw new IllegalArgumentException("Коэффициентов должно быть ровно " + levels + " — по одному на уровень");
    double[] ladder = new double[levels];
    double previous = 1.0;
    for (int i = 0; i < levels; i++) {
      if (!Double.isFinite(requested[i]))
        throw new IllegalArgumentException("Коэффициент уровня должен быть конечным числом");
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
      proof.put("formula", "hazard(1) = 0; hazard(level >= 2) = min(maxHazard, baseHazard * hazardGrowth^(level-1)); шар лопается на первом уровне, где HMAC_SHA256(seed, roundId:level) < hazard(level)");
    }
    return proof;
  }

  public Map<String, Object> proofOwned(String id, String username) {
    return proofOwnedRound(getOwned(id, username));
  }

  private Map<String, Object> proofOwnedRound(Round round) {
    Map<String, Object> proof = new LinkedHashMap<>();
    proof.put("roundId", round.id);
    proof.put("commitment", "sha256:" + round.commitment);
    proof.put("configVersion", round.config.version());
    if (round.finished()) {
      proof.put("seed", round.seed);
      proof.put("popLevel", round.popLevel);
      proof.put("boosterLevel", round.boosterLevel);
      proof.put("ladder", round.ladder);
      proof.put("crashMultiplier", round.crashMultiplier());
      proof.put("formula", "hazard(1) = 0; hazard(level >= 2) = min(maxHazard, baseHazard * hazardGrowth^(level-1)); шар лопается на первом уровне, где HMAC_SHA256(seed, roundId:level) < hazard(level)");
    }
    return proof;
  }

  /** Один такт полёта. Виден тестам пакета: они двигают раунд по уровням без ожидания таймера. */
  void tick(Round round) {
    synchronized (round) {
      try {
        if (round.flightOver) {
          // A failed final side effect is retried safely: every effect is
          // committed together with its marker on the round row.
          if (!round.settlementComplete) endFlight(round);
          else stopTicker(round.id);
          return;
        }
        if (round.level >= round.levels()) {
          // Шар уже на вершине, но прошлое начисление не удалось: повторяем его, не поднимая уровень.
          if (round.cashedOut()) endFlight(round);
          else payout(round, RoundState.SETTLED);
          return;
        }
        int next = round.level + 1;

        if (next >= round.popLevel) {
          // Шар лопнул. Если выигрыш уже забрали, он остаётся за игроком.
          if (round.cashedOut()) endFlight(round);
          else finish(round, RoundState.CRASHED);
          return;
        }

        round.level = next;
        // Пересечение уровня всегда приносит базовые очки, даже если игрок
        // уже сделал cashout: полёт продолжается до crash именно для этого
        // сценария. Бустер после cashout намеренно не активируется.
        round.points += round.config.pointsPerLevel();
        if (!round.cashedOut() && next == round.boosterLevel && !round.boosterActivated) {
          round.boosterActivated = true;
          round.points += (int) Math.round(round.config.pointsBoosterBonus() * round.boosterMultiplier);
        }
        round.multiplier = round.multiplierAt(next);

        // Шар долетел до вершины.
        if (next >= round.levels()) {
          if (round.cashedOut()) endFlight(round);
          else payout(round, RoundState.SETTLED);
        }
      } catch (RuntimeException e) {
        // Периодическая задача, бросившая исключение, больше не запускается: без перехвата раунд завис бы до перезапуска.
        log.error("Сбой такта раунда {}: {}", round.id, e.getMessage(), e);
      }
    }
  }

  private void payout(Round round, RoundState state) {
    double settledMultiplier = round.multiplier;
    long settledPayout = Math.max(1, Math.round(round.betAmount * settledMultiplier));
    int settledPoints = Math.addExact(round.points, round.config.pointsCashoutBonus());

    // Выплата, счётчик выигрышей и запись раунда — одна транзакция: баланс игрока и раунд в базе
    // не расходятся даже при падении сервера, а предложение «Закрепи успех» решено к экрану результата.
    PayoutOutcome outcome = transactions.execute(status -> {
      long balance = accounts.credit(round.userId, settledPayout).getBalance();
      var win = accounts.registerWin(round.userId, settledPayout, round.config.minWinAmount(), LotteryService.OFFER_EVERY_WINS);
      store.update(round.id, entity -> entity.recordPayout(state.name(), settledMultiplier, settledPayout, settledPoints,
          balance, round.level, round.multiplier, round.boosterActivated, win.wins()));
      return new PayoutOutcome(balance, win);
    });

    // Mark the in-memory state only after the transaction commits,
    // so a failed payout remains safely retryable.
    round.cashedOutMultiplier = settledMultiplier;
    round.payout = settledPayout;
    round.points = settledPoints;
    round.state = state;
    round.balanceAfter = outcome.balanceAfter();
    round.winNumber = outcome.win().wins();
    round.offerDue = outcome.win().offerDue();
    round.winSettled = true;
    // Cashout только фиксирует выплату: полёт останавливается отдельно, когда шар лопнет.
    if (state != RoundState.CASHED_OUT) endFlight(round);
  }

  private void finish(Round round, RoundState state) {
    round.state = state;
    endFlight(round);
  }

  private void endFlight(Round round) {
    // `flightOver` becomes visible before the side effects below are done, so
    // retention must use the explicit settlement state rather than `finished`
    // alone.  Setting `settling` first closes the small race with a concurrent
    // start request pruning old history.
    if (round.settlementComplete || round.settling) return;
    round.settling = true;
    try {
      if (!round.flightOver) {
        Instant endedAt = Instant.now();
        // Конец полёта записывается до начислений: лента и история видят раунд сразу,
        // а после перезапуска полёт не доигрывается второй раз.
        store.update(round.id, entity -> entity.recordFlightEnd(round.state.name(), round.level, round.multiplier,
            round.points, round.boosterActivated, endedAt));
        round.flightOver = true;
        round.endedAt = endedAt;
      }

      // Level/cashout/booster points are settled once, after the flight has
      // actually ended. This also includes levels crossed after cashout.
      // Each effect commits together with its marker on the round row, so a
      // retry after a database failure or a restart never credits anything twice.
      if (!round.pointsSettled) {
        try {
          transactions.executeWithoutResult(status -> {
            accounts.addPoints(round.userId, round.points);
            store.update(round.id, RoundEntity::markPointsSettled);
          });
          round.pointsSettled = true;
        } catch (Exception e) {
          log.error("Не удалось начислить очки раунда {} пользователю {}: {}", round.id, round.userId, e.getMessage(), e);
          if (isTerminalSettlementFailure(e)) round.pointsSettled = markSettled(round, RoundEntity::markPointsSettled);
        }
      }

      // Награда выдаётся по итогам раунда независимо от выигрыша или проигрыша.
      if (!round.rewardSettled) {
        try {
          // null is a valid result after today's ticket is already issued.
          PuzzleAward reward = transactions.execute(status -> {
            PuzzleAward award = accounts.awardPuzzleFragment(round.userId);
            store.update(round.id, entity -> entity.recordReward(store.json(award)));
            return award;
          });
          round.reward = reward;
          round.rewardSettled = true;
        } catch (Exception e) {
          log.warn("Не удалось выдать ежедневный фрагмент пазла для раунда {} пользователя {}: {}",
              round.id, round.userId, e.getMessage());
          if (isTerminalSettlementFailure(e)) round.rewardSettled = markSettled(round, RoundEntity::markRewardSettled);
        }
      }

      try { round.balanceAfter = playerBalance(round); }
      catch (Exception e) {
        log.warn("Не удалось получить баланс после раунда {} пользователя {}: {}", round.id, round.userId, e.getMessage());
      }
      if (round.pointsSettled && round.rewardSettled && (round.payout == 0 || round.winSettled)) {
        long balanceAfter = round.balanceAfter;
        store.update(round.id, entity -> entity.completeSettlement(balanceAfter));
        round.settlementComplete = true;
      }
    } catch (Exception e) {
      // Запись в базу не удалась: следующий такт повторит завершение с того же места.
      log.error("Не удалось сохранить итоги раунда {}: {}", round.id, e.getMessage(), e);
    } finally {
      // Keep `settling` set until all effects have been marked complete and
      // the ticker has been cancelled; prune observes both flags.
      // Stop the ticker first while `settling` is still true.  Otherwise a
      // concurrent start/prune could remove this round in the tiny interval
      // between clearing the flag and cancelling its scheduled task.
      try {
        if (round.settlementComplete) stopTicker(round.id);
      } finally {
        round.settling = false;
      }
      if (round.settlementComplete) pruneRetainedRounds();
    }
  }

  /** Помечает начисление выполненным без самого начисления — для ошибок, которые повтор не исправит. */
  private boolean markSettled(Round round, Consumer<RoundEntity> change) {
    try {
      store.update(round.id, change);
      return true;
    } catch (Exception e) {
      log.warn("Не удалось отметить итог раунда {}: {}", round.id, e.getMessage());
      return false;
    }
  }

  /** Account state errors are not recoverable by a later scheduler tick. */
  private static boolean isTerminalSettlementFailure(Exception e) {
    return e instanceof SecurityException || e instanceof NoSuchElementException;
  }

  private long playerBalance(Round round) {
    return accounts.byUsername(round.userId).getBalance();
  }

  private void stopTicker(String id) {
    ScheduledFuture<?> ticker = tickers.remove(id);
    if (ticker != null) ticker.cancel(false);
  }

  private void remember(Round round) {
    rounds.put(round.id, round);
    roundOrder.add(round.id);
    pruneRetainedRounds();
  }

  /**
   * Bounds completed rounds kept in memory without ever deleting an active round
   * or leaving its scheduled ticker orphaned.  If more than HISTORY_LIMIT rounds
   * are simultaneously active, they are all retained until some complete.
   */
  private void pruneRetainedRounds() {
    // Never evict a live round merely because many players started at once:
    // that would make its owner unable to cash out.  Finished rounds are
    // pruned first; a burst of more than HISTORY_LIMIT active rounds is kept
    // temporarily and becomes prunable as flights end.
    while (rounds.size() > HISTORY_LIMIT) {
      boolean removed = false;
      for (String candidate : roundOrder) {
        Round candidateRound = rounds.get(candidate);
        if (candidateRound == null) {
          roundOrder.remove(candidate);
          continue;
        }
        // A finished round may still be crediting points, awarding a puzzle
        // fragment, or registering a win.  It stays addressable and retains
        // its idempotency record until all those effects are complete.
        if (candidateRound.finished() && candidateRound.settlementComplete
            && !candidateRound.settling && rounds.remove(candidate, candidateRound)) {
          roundOrder.remove(candidate);
          forgetIdempotencyFor(candidate);
          removed = true;
          break;
        }
      }
      if (!removed) break;
    }
  }

  /** Remove in-memory retry records once their completed round leaves retention. */
  private void forgetIdempotencyFor(String roundId) {
    synchronized (idempotencyLock) {
      idempotent.entrySet().removeIf(entry -> entry.getValue().round().id.equals(roundId));
    }
  }

  /** Первый уровень, на котором шар лопается; levels+1 означает, что шар долетел до вершины. */
  private int popLevel(String seed, String id, Config config) {
    for (int level = 1; level <= config.levels(); level++) {
      if (hmacUnit(seed, id + ":" + level) < config.hazardAt(level)) return level;
    }
    return config.levels() + 1;
  }

  private int optionIndex(String option, Theme theme, int count) {
    if (option == null || option.isBlank())
      throw new IllegalArgumentException("Вариант ставки обязателен");
    String value = option.trim();
    if (!option.equals(value))
      throw new IllegalArgumentException("Неизвестный вариант ставки: " + option);
    String prefix = theme.name().toLowerCase(Locale.ROOT) + "-x";
    if (!value.startsWith(prefix) || value.length() == prefix.length())
      throw new IllegalArgumentException("Неизвестный вариант ставки: " + option);
    String suffix = value.substring(prefix.length());
    if (!suffix.matches("[1-9][0-9]*"))
      throw new IllegalArgumentException("Неизвестный вариант ставки: " + option);
    try {
      int n = Integer.parseInt(suffix);
      if (n < 1 || n > count) throw new IllegalArgumentException("Неизвестный вариант ставки: " + option);
      return n - 1;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Неизвестный вариант ставки: " + option);
    }
  }

  private static String normalizeIdempotencyKey(String value) {
    if (value == null) return null;
    if (value.isBlank()) return null;
    if (value.length() > 128) throw new IllegalArgumentException("Idempotency-Key должен быть не длиннее 128 символов");
    if (value.chars().anyMatch(Character::isWhitespace))
      throw new IllegalArgumentException("Idempotency-Key не должен содержать пробелы");
    return value;
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
