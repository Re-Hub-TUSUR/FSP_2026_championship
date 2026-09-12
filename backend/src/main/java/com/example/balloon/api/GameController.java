package com.example.balloon.api;

import com.example.balloon.application.GameService;
import com.example.balloon.application.LeaderboardService;
import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
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

  public GameController(GameService game, AccountService accounts, LeaderboardService leaderboard) {
    this.game = game;
    this.accounts = accounts;
    this.leaderboard = leaderboard;
  }

  public record StartRequest(@NotNull Theme theme, @NotBlank String betOptionId, String idempotencyKey) {}

  @GetMapping("/me")
  public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String auth) {
    try { return ResponseEntity.ok(AuthController.profile(accounts.require(auth))); }
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
        Map.of("heading", "Взрыв шара", "text", "На каждом уровне шар проходит проверку на разрыв. Шанс взрыва растёт с высотой, поэтому долгий полёт всегда рискован."),
        Map.of("heading", "Cashout", "text", "Заберите выигрыш после первого уровня. Если шар долетел до последнего уровня, выплата начисляется автоматически."),
        Map.of("heading", "Бустеры", "text", "Бустер ставки умножает бонусные очки на своём уровне и не влияет на коэффициент выплаты.")));
  }

  @PostMapping("/rounds")
  public ResponseEntity<?> start(@Valid @RequestBody StartRequest req,
                                 @RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    try {
      AccountEntity user = accounts.require(auth);
      String idempotencyKey = key != null ? key : req.idempotencyKey();
      return ResponseEntity.status(HttpStatus.CREATED).body(toMap(game.startIdempotent(user.getUsername(), req.theme(), req.betOptionId(), idempotencyKey)));
    } catch (SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/rounds/history")
  public List<Map<String, Object>> history(@RequestParam(defaultValue = "50") int limit) {
    return game.history().stream().limit(Math.max(1, Math.min(100, limit))).map(this::toMap).toList();
  }

  @GetMapping("/rounds/{id}")
  public Map<String, Object> get(@PathVariable String id) { return toMap(game.get(id)); }

  @PostMapping("/rounds/{id}/cashout")
  public ResponseEntity<?> cashout(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth) {
    try { accounts.require(auth); return ResponseEntity.ok(toMap(game.cashout(id))); }
    catch (SecurityException e) { return ResponseEntity.status(401).body(Map.of("error", e.getMessage())); }
    catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
  }

  @GetMapping("/rounds/{id}/proof")
  public Map<String, Object> proof(@PathVariable String id) { return game.proof(id); }

  @GetMapping("/leaderboard")
  public Map<String, Object> leaderboard(@RequestParam(defaultValue = "") String q,
                                         @RequestParam(defaultValue = "score") String sort,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "50") int limit) {
    return Map.of("items", leaderboard.entries(q, sort, offset, limit), "updatedAt", Instant.now());
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
    double[] ladderOverride = doubles(body.get("ladderOverride"));

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
        ladderOverride, old.version());
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
    for (int i = 0; i < config.bets().length; i++)
      options.add(Map.of("id", config.theme().name().toLowerCase() + "-x" + (i + 1),
          "amount", config.bets()[i],
          "boosterMultiplier", config.boosters()[i]));

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
      map.put("seed", round.seed);
    }
    return map;
  }
}
