package com.example.balloon.api;

import com.example.balloon.application.GameService;
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

  public GameController(GameService game) { this.game = game; }

  public record StartRequest(@NotNull Theme theme, @NotBlank String betOptionId, String idempotencyKey) {}

  @GetMapping("/me")
  public Map<String, Object> me() {
    return Map.of("userId", "demo", "username", "demo", "role", "PLAYER", "balance", game.balance());
  }

  @GetMapping("/game/config")
  public Map<String, Object> config(@RequestParam(defaultValue = "RED") Theme theme) {
    return configMap(theme, game.config(theme));
  }

  @GetMapping("/rules")
  public Map<String, Object> rules() {
    return Map.of("title", "Правила игры", "sections", List.of(
        Map.of("heading", "Темы и уровни", "text", "Красная тема содержит 12 уровней, зелёная — 9. Каждый уровень поднимает коэффициент."),
        Map.of("heading", "Рост коэффициентов", "text", "Коэффициент растёт экспоненциально: чем выше шар, тем больше давление и тем дороже следующий уровень."),
        Map.of("heading", "Взрыв шара", "text", "На каждом уровне шар проходит проверку на разрыв. Шанс взрыва растёт с высотой, поэтому долгий полёт всегда рискован."),
        Map.of("heading", "Cashout", "text", "Заберите выигрыш после первого уровня. Если шар долетел до последнего уровня, выплата начисляется автоматически."),
        Map.of("heading", "Бустеры", "text", "Бустер ставки умножает бонусные очки на своём уровне и не влияет на коэффициент выплаты.")));
  }

  @PostMapping("/rounds")
  public ResponseEntity<?> start(@Valid @RequestBody StartRequest req,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    try {
      String idempotencyKey = key != null ? key : req.idempotencyKey();
      return ResponseEntity.status(HttpStatus.CREATED).body(toMap(game.startIdempotent("demo", req.theme(), req.betOptionId(), idempotencyKey)));
    } catch (IllegalStateException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "balance", game.balance()));
    }
  }

  @GetMapping("/rounds/history")
  public List<Map<String, Object>> history(@RequestParam(defaultValue = "50") int limit) {
    int size = Math.max(1, Math.min(100, limit));
    return game.history().stream().limit(size).map(this::toMap).toList();
  }

  @GetMapping("/rounds/{id}")
  public Map<String, Object> get(@PathVariable String id) { return toMap(game.get(id)); }

  @PostMapping("/rounds/{id}/cashout")
  public ResponseEntity<?> cashout(@PathVariable String id) {
    try {
      return ResponseEntity.ok(toMap(game.cashout(id)));
    } catch (IllegalStateException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/rounds/{id}/proof")
  public Map<String, Object> proof(@PathVariable String id) { return game.proof(id); }

  @GetMapping("/admin/config")
  public Map<String, Object> adminConfig(@RequestParam(defaultValue = "RED") Theme theme) {
    return configMap(theme, game.config(theme));
  }

  @PutMapping("/admin/config")
  public Map<String, Object> update(@RequestParam(defaultValue = "RED") Theme theme, @RequestBody Map<String, Object> body) {
    return configMap(theme, game.updateConfig(theme, parseConfig(theme, body)));
  }

  private Config parseConfig(Theme theme, Map<String, Object> body) {
    Config old = game.config(theme);
    long[] bets = old.bets();
    double[] boosters = old.boosters();
    List<?> options = body.get("betOptions") instanceof List<?> list ? list : null;
    if (options != null && options.size() == 4) {
      bets = new long[4];
      boosters = new double[4];
      for (int i = 0; i < 4; i++) {
        Map<?, ?> option = (Map<?, ?>) options.get(i);
        bets[i] = option.get("amount") instanceof Number n ? n.longValue() : old.bets()[i];
        boosters[i] = option.get("boosterMultiplier") instanceof Number n ? n.doubleValue() : old.boosters()[i];
      }
    }
    return new Config(theme,
        number(body, "levels", old.levels()).intValue(),
        bets, boosters,
        number(body, "pointsPerLevel", old.pointsPerLevel()).intValue(),
        number(body, "pointsCashoutBonus", old.pointsCashoutBonus()).intValue(),
        number(body, "pointsBoosterBonus", old.pointsBoosterBonus()).intValue(),
        number(body, "baseHazard", old.baseHazard()).doubleValue(),
        number(body, "hazardGrowth", old.hazardGrowth()).doubleValue(),
        number(body, "maxHazard", old.maxHazard()).doubleValue(),
        number(body, "rtp", old.rtp()).doubleValue(),
        old.version());
  }

  private static Number number(Map<String, Object> body, String key, Number fallback) {
    return body.get(key) instanceof Number n ? n : fallback;
  }

  private Map<String, Object> configMap(Theme theme, Config config) {
    List<Map<String, Object>> options = new ArrayList<>();
    for (int i = 0; i < config.bets().length; i++)
      options.add(Map.of("id", theme.name().toLowerCase() + "-x" + (i + 1),
          "amount", config.bets()[i],
          "boosterMultiplier", config.boosters()[Math.min(i, config.boosters().length - 1)]));

    double[] ladder = config.ladder();
    List<Map<String, Object>> levels = new ArrayList<>();
    for (int level = 1; level < ladder.length; level++)
      levels.add(Map.of("level", level,
          "multiplier", ladder[level],
          "popChance", Math.round(config.hazardAt(level) * 1000.0) / 1000.0));

    Map<String, Object> map = new LinkedHashMap<>();
    map.put("gameId", "balloon");
    map.put("gameName", "Воздушный шар");
    map.put("theme", theme);
    map.put("levels", config.levels());
    map.put("betOptions", options);
    map.put("pointsPerLevel", config.pointsPerLevel());
    map.put("pointsCashoutBonus", config.pointsCashoutBonus());
    map.put("pointsBoosterBonus", config.pointsBoosterBonus());
    map.put("ladder", Arrays.stream(ladder).boxed().toList());
    map.put("levelTable", levels);
    map.put("maxMultiplier", ladder[config.levels()]);
    map.put("rtp", config.rtp());
    map.put("baseHazard", config.baseHazard());
    map.put("hazardGrowth", config.hazardGrowth());
    map.put("maxHazard", config.maxHazard());
    map.put("version", config.version());
    return map;
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
    map.put("profit", round.finished() ? round.profit() : 0);
    map.put("points", round.points);
    map.put("balance", game.balance());
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
