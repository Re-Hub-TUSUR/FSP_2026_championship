package com.example.balloon.api;

import com.example.balloon.application.GameService;
import com.example.balloon.application.LeaderboardService;
import com.example.balloon.application.LotteryService;
import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.daily.DailyBonusService;
import com.example.balloon.domain.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", maxAge = 3600)
public class GameController {
  private final GameService game;
  private final AccountService accounts;
  private final LeaderboardService leaderboard;
  private final DailyBonusService dailyBonus;

  public GameController(GameService game, AccountService accounts, LeaderboardService leaderboard, DailyBonusService dailyBonus) {
    this.game = game;
    this.accounts = accounts;
    this.leaderboard = leaderboard;
    this.dailyBonus = dailyBonus;
  }

  public record StartRequest(@NotNull Theme theme, @NotBlank String betOptionId, String idempotencyKey) {}

  @GetMapping("/me")
  public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      return ResponseEntity.ok(AuthController.profile(account, dailyBonus.state(account.getId())));
    }
    catch (Exception e) { return ResponseEntity.status(401).body(Map.of("error", e.getMessage())); }
  }

  @GetMapping("/game/config")
  public Map<String, Object> config(@RequestParam(defaultValue = "RED") Theme theme) {
    return configMap(game.config(theme));
  }

  @GetMapping("/rules")
  public Map<String, Object> rules() {
    Config red = game.config(Theme.RED);
    Config green = game.config(Theme.GREEN);
    return Map.of("title", "Правила игры", "sections", List.of(
        Map.of("heading", "Темы и уровни", "text", "Красная тема содержит " + red.levels() + " уровней, зелёная — " + green.levels() + ". Каждый уровень поднимает коэффициент."),
        Map.of("heading", "Рост коэффициентов", "text", "Коэффициент растёт экспоненциально: чем выше шар, тем больше давление и тем дороже следующий уровень."),
        Map.of("heading", "Взрыв шара", "text", "Первый подъём проходит без риска, а начиная со второго уровня шар на каждом шаге проходит проверку на разрыв. Шанс взрыва растёт с высотой, поэтому долгий полёт всегда рискован."),
        Map.of("heading", "Cashout", "text", "Заберите выигрыш начиная со второго уровня — коэффициент фиксируется сразу. Шар продолжает лететь до взрыва, и только потом открывается экран результата. Если шар долетел до последнего уровня, выплата начисляется автоматически."),
        Map.of("heading", "Бустеры", "text", "Бустер ставки ждёт на случайном уровне начиная с третьего. Если шар долетит до него раньше, чем вы заберёте выигрыш, коэффициент умножается на значение бустера и начисляются дополнительные очки. После нажатия «Забрать» бустер не срабатывает."),
        Map.of("heading", "Цена бустера", "text", "Базовые коэффициенты тира с бустером ниже ровно настолько, насколько в среднем добавляет сам бустер, поэтому возврат игроку одинаков для всех вариантов ставки."),
        Map.of("heading", "Ежедневный билет", "text", "За каждый завершённый раунд — и выигранный, и проигранный — вы получаете случайный недостающий фрагмент сегодняшнего пазла. Собрав все "
            + AccountService.PUZZLE_FRAGMENTS + " фрагментов за день, вы открываете один лотерейный билет. Его награда определяется сервером при стирании и начисляется на баланс."),
        Map.of("heading", "Закрепи успех", "text", "После удачного раунда с выигрышем от " + red.minWinAmount()
            + " ★ можно закрепить результат — купить лотерейные билеты за бонусные баллы со скидкой. Предложение приходит "
            + LotteryService.offerFrequency() + "; если выигрыш меньше порога, оно переходит на ближайший подходящий. Окно закрывается само через "
            + red.popupTimeout() + " секунд. Билеты также продаются во вкладке «Лотерея».")));
  }

  @PostMapping("/rounds")
  public ResponseEntity<?> start(@Valid @RequestBody StartRequest req,
                                 @RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    try {
      AccountEntity user = accounts.require(auth);
      // A proxy may send an empty header.  In that case use the body key
      // rather than silently disabling idempotency for this start request.
      String idempotencyKey = key != null && !key.isBlank() ? key : req.idempotencyKey();
      return ResponseEntity.status(HttpStatus.CREATED).body(toMap(game.startIdempotent(user.getUsername(), req.theme(), req.betOptionId(), idempotencyKey)));
    } catch (SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/rounds/history")
  public List<Map<String, Object>> history(@RequestParam(defaultValue = "50") int limit) {
    // История по условию общая для всех игроков, но приватные поля (seed,
    // commitment, баланс и награда) не должны раскрываться в общей ленте.
    return game.history(Math.max(1, Math.min(100, limit))).stream().map(this::toHistoryMap).toList();
  }

  /** Завершённые раунды текущего игрока с наградой и commitment — для вкладки «Мои раунды» и проверки честности. */
  @GetMapping("/rounds/mine")
  public ResponseEntity<?> mine(@RequestParam(defaultValue = "50") int limit,
                                @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      return ResponseEntity.ok(game.historyOf(account.getUsername(), Math.max(1, Math.min(100, limit))).stream()
          .map(this::toMap)
          .toList());
    } catch (SecurityException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }
  }

  /** Компактная лента «Прошлые игры»: коэффициенты взрыва завершённых раундов всех игроков. */
  @GetMapping("/rounds/crashes")
  public List<Map<String, Object>> crashes(@RequestParam(defaultValue = "20") int limit) {
    return game.history(Math.max(1, Math.min(50, limit))).stream()
        .map(round -> Map.<String, Object>of(
            "roundId", round.id,
            "theme", round.theme(),
            "crashMultiplier", round.crashMultiplier(),
            "survived", round.popLevel > round.levels(),
            "endedAt", round.endedAt == null ? round.startedAt : round.endedAt))
        .toList();
  }

  @GetMapping("/rounds/{id}")
  public ResponseEntity<?> get(@PathVariable String id,
                               @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      return ResponseEntity.ok(toMap(game.getOwned(id, account.getUsername())));
    } catch (SecurityException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/rounds/{id}/cashout")
  public ResponseEntity<?> cashout(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      return ResponseEntity.ok(toMap(game.cashoutOwned(id, account.getUsername())));
    }
    catch (SecurityException e) { return ResponseEntity.status(401).body(Map.of("error", e.getMessage())); }
    catch (NoSuchElementException e) { return ResponseEntity.status(404).body(Map.of("error", e.getMessage())); }
    catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
  }

  @GetMapping("/rounds/{id}/proof")
  public ResponseEntity<?> proof(@PathVariable String id,
                                 @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      return ResponseEntity.ok(game.proofOwned(id, account.getUsername()));
    } catch (SecurityException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/leaderboard")
  public Map<String, Object> leaderboard(@RequestParam(defaultValue = "") String q,
                                         @RequestParam(defaultValue = "score") String sort,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "50") int limit) {
    return Map.of("items", leaderboard.entries(q, sort, offset, limit), "updatedAt", Instant.now());
  }

  @GetMapping("/tournament")
  public Map<String,Object> tournament() {
    leaderboard.settleTournament();
    var t = leaderboard.tournament();
    return Map.of("startedAt", t.startedAt(), "endsAt", t.endsAt(), "active", t.active(), "firstPrize", t.firstPrize(), "secondPrize", t.secondPrize(), "thirdPrize", t.thirdPrize(), "prizesPaid", t.prizesPaid());
  }

  @GetMapping("/admin/config")
  public ResponseEntity<?> adminConfig(@RequestParam(defaultValue = "RED") Theme theme,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
    try { accounts.requireAdmin(auth); return ResponseEntity.ok(configMap(game.config(theme))); }
    catch (SecurityException e) { return ResponseEntity.status(403).body(Map.of("error", e.getMessage())); }
  }

  @PutMapping("/admin/config")
  public ResponseEntity<?> update(@RequestParam(defaultValue = "RED") Theme theme,
                                  @RequestBody Map<String, Object> body,
                                  @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity admin = accounts.requireAdmin(auth);
      return ResponseEntity.ok(configMap(game.updateConfig(theme, parseConfig(theme, body), admin.getUsername())));
    } catch (SecurityException e) {
      return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/admin/config/reset")
  public ResponseEntity<?> reset(@RequestParam(defaultValue = "RED") Theme theme,
                                 @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity admin = accounts.requireAdmin(auth);
      return ResponseEntity.ok(configMap(game.resetConfig(theme, admin.getUsername())));
    } catch (SecurityException e) {
      return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  private Config parseConfig(Theme theme, Map<String, Object> body) {
    Config old = game.config(theme);
    int levels = number(body, "levels", old.levels()).intValue();

    long[] bets = old.bets();
    double[] boosters = old.boosters();
    if (body.get("betOptions") instanceof List<?> options && options.size() == 4) {
      bets = new long[4];
      boosters = new double[4];
      for (int i = 0; i < 4; i++) {
        Map<?, ?> option = (Map<?, ?>) options.get(i);
        bets[i] = option.get("amount") instanceof Number n ? n.longValue() : old.bets()[i];
        boosters[i] = option.get("boosterMultiplier") instanceof Number n ? n.doubleValue() : old.boosters()[i];
      }
    }

    double[] weights = doubles(body.get("boosterWeights"));
    if (weights.length == 0) weights = old.boosterWeights();
    // Partial admin updates must not silently erase a manually published
    // ladder.  An explicitly supplied empty list remains the intentional
    // command to return to the formula-based ladder.
    double[] previousLadder = old.ladderOverride();
    double[] ladderOverride = body.containsKey("ladderOverride")
        ? doubles(body.get("ladderOverride"))
        : previousLadder == null ? new double[0] : Arrays.copyOf(previousLadder, previousLadder.length);

    return new Config(theme,
        text(body, "gameId", old.gameId()),
        text(body, "gameName", old.gameName()),
        text(body, "gameType", old.gameType()),
        body.get("active") instanceof Boolean active ? active : old.active(),
        levels, bets, boosters, weights,
        number(body, "pointsPerLevel", old.pointsPerLevel()).intValue(),
        number(body, "pointsCashoutBonus", old.pointsCashoutBonus()).intValue(),
        number(body, "pointsBoosterBonus", old.pointsBoosterBonus()).intValue(),
        number(body, "baseHazard", old.baseHazard()).doubleValue(),
        number(body, "hazardGrowth", old.hazardGrowth()).doubleValue(),
        number(body, "maxHazard", old.maxHazard()).doubleValue(),
        number(body, "rtp", old.rtp()).doubleValue(),
        ladderOverride,
        number(body, "minWinAmount", old.minWinAmount()).longValue(),
        number(body, "popupTimeout", old.popupTimeout()).intValue(),
        old.version());
  }

  private static Number number(Map<String, Object> body, String key, Number fallback) {
    return body.get(key) instanceof Number n ? n : fallback;
  }

  private static String text(Map<String, Object> body, String key, String fallback) {
    return body.get(key) instanceof String s && !s.isBlank() ? s : fallback;
  }

  private static double[] doubles(Object value) {
    if (!(value instanceof List<?> list)) return new double[0];
    double[] result = new double[list.size()];
    for (int i = 0; i < list.size(); i++) result[i] = list.get(i) instanceof Number n ? n.doubleValue() : 0;
    return result;
  }

  private Map<String, Object> configMap(Config config) {
    List<Map<String, Object>> options = new ArrayList<>();
    for (int i = 0; i < config.bets().length; i++) {
      double[] tierLadder = config.ladderForBooster(config.boosters()[i]);
      options.add(Map.of("id", config.theme().name().toLowerCase() + "-x" + (i + 1),
          "amount", config.bets()[i],
          "boosterMultiplier", config.boosters()[i],
          // Чем сильнее бустер, тем реже он выпадает — возврат игроку одинаков для всех тиров.
          "boosterChance", round(config.boosterAppearChance(config.boosters()[i]), 3),
          "ladder", Arrays.stream(Arrays.copyOfRange(tierLadder, 1, tierLadder.length)).boxed().toList()));
    }

    double[] ladder = config.ladder();
    double[] boosterChances = config.boosterProbabilities();
    List<Map<String, Object>> levelTable = new ArrayList<>();
    for (int level = 1; level <= config.levels(); level++)
      levelTable.add(Map.of("level", level,
          "multiplier", ladder[level],
          "popChance", round(config.hazardAt(level), 4),
          "boosterChance", round(boosterChances[level - 1], 4),
          "expectedReturn", round(config.expectedReturnAt(level), 4)));

    Map<String, Object> map = new LinkedHashMap<>();
    map.put("gameId", config.gameId());
    map.put("gameName", config.gameName());
    map.put("gameType", config.gameType());
    map.put("active", config.active());
    map.put("theme", config.theme());
    map.put("levels", config.levels());
    map.put("betOptions", options);
    map.put("pointsPerLevel", config.pointsPerLevel());
    map.put("pointsCashoutBonus", config.pointsCashoutBonus());
    map.put("pointsBoosterBonus", config.pointsBoosterBonus());
    map.put("baseHazard", config.baseHazard());
    map.put("hazardGrowth", config.hazardGrowth());
    map.put("maxHazard", config.maxHazard());
    map.put("rtp", config.rtp());
    map.put("boosterWeights", Arrays.stream(config.boosterProbabilities()).boxed().toList());
    map.put("ladder", Arrays.stream(ladder).boxed().toList());
    // Пустой список означает, что лестница считается по формуле, а не задана вручную.
    map.put("ladderOverride", config.hasCustomLadder() ? Arrays.stream(config.ladderOverride()).boxed().toList() : List.of());
    map.put("ladderSource", config.hasCustomLadder() ? "custom" : "formula");
    map.put("levelTable", levelTable);
    map.put("maxMultiplier", ladder[config.levels()]);
    map.put("minWinAmount", config.minWinAmount());
    map.put("popupTimeout", config.popupTimeout());
    map.put("version", config.version());
    return map;
  }

  private static double round(double value, int digits) {
    double factor = Math.pow(10, digits);
    return Math.round(value * factor) / factor;
  }

  private Map<String, Object> toMap(Round round) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("roundId", round.id);
    map.put("state", round.state);
    map.put("theme", round.theme());
    map.put("betAmount", round.betAmount);
    map.put("boosterMultiplier", round.boosterMultiplier);
    map.put("boosterLevel", round.boosterLevel);
    map.put("boosterActivated", round.boosterActivated);
    map.put("cashedOut", round.cashedOut());
    map.put("flightOver", round.flightOver);
    map.put("levels", round.levels());
    map.put("level", round.level);
    map.put("multiplier", round.multiplier);
    map.put("nextMultiplier", round.nextMultiplier());
    map.put("ladder", Arrays.stream(round.ladder).boxed().toList());
    map.put("potentialPayout", Math.round(round.betAmount * round.multiplier));
    map.put("payout", round.payout);
    map.put("profit", round.profit());
    map.put("points", round.points);
    map.put("balanceAfter", round.balanceAfter);
    map.put("commitment", "sha256:" + round.commitment);
    map.put("serverTime", Instant.now());
    map.put("createdAt", round.startedAt);
    if (round.cashedOutMultiplier != null) map.put("cashedOutMultiplier", round.cashedOutMultiplier);
    if (round.endedAt != null) map.put("endedAt", round.endedAt);
    if (round.finished()) {
      map.put("popLevel", round.popLevel);
      map.put("crashMultiplier", round.crashMultiplier());
      map.put("potentialMax", round.potentialMax());
      map.put("seed", round.seed);
      if (round.reward != null) map.put("reward", round.reward);
    }
    return map;
  }

  /** Public history projection; ownership-sensitive fields stay private. */
  private Map<String, Object> toHistoryMap(Round round) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("roundId", round.id);
    map.put("state", round.state);
    map.put("theme", round.theme());
    map.put("betAmount", round.betAmount);
    map.put("boosterMultiplier", round.boosterMultiplier);
    map.put("boosterLevel", round.boosterLevel);
    map.put("boosterActivated", round.boosterActivated);
    map.put("cashedOut", round.cashedOut());
    map.put("flightOver", round.flightOver);
    map.put("levels", round.levels());
    map.put("level", round.level);
    map.put("multiplier", round.multiplier);
    map.put("nextMultiplier", round.nextMultiplier());
    map.put("potentialPayout", Math.round(round.betAmount * round.multiplier));
    map.put("payout", round.payout);
    map.put("profit", round.profit());
    map.put("points", round.points);
    if (round.cashedOutMultiplier != null) map.put("cashedOutMultiplier", round.cashedOutMultiplier);
    if (round.finished()) {
      map.put("popLevel", round.popLevel);
      map.put("crashMultiplier", round.crashMultiplier());
      map.put("potentialMax", round.potentialMax());
    }
    map.put("createdAt", round.startedAt);
    if (round.endedAt != null) map.put("endedAt", round.endedAt);
    return map;
  }
}
