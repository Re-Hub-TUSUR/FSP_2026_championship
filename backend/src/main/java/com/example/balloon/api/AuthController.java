package com.example.balloon.api;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.daily.DailyBonusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthController {
  private final AccountService service;
  private final DailyBonusService dailyBonus;

  public AuthController(AccountService service, DailyBonusService dailyBonus) {
    this.service = service;
    this.dailyBonus = dailyBonus;
  }

  public record RegisterRequest(
      @NotBlank @Size(min = 3, max = 40)
      String username,
      @NotBlank(message = "Пароль обязателен")
      @Size(min = 6, max = 128, message = AccountService.PASSWORD_REQUIREMENTS)
      String password) {}
  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  @PostMapping("/register")
  public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
    try { return ResponseEntity.status(201).body(profileWithDaily(service.register(request.username(), request.password()))); }
    catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
    try {
      String token = service.login(request.username(), request.password());
      return ResponseEntity.ok(Map.of("token", token, "user", profileWithDaily(service.current(token))));
    } catch (Exception e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
    service.logout(auth);
    return Map.of("ok", true);
  }

  /**
   * Adds the fixed demo amount on the server.  The browser must never mutate
   * its displayed balance locally, so this endpoint requires the same bearer
   * authentication as all other account operations.
   */
  @PostMapping("/topup")
  public ResponseEntity<?> topup(@RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = service.require(auth);
      return ResponseEntity.ok(profileWithDaily(service.topUp(account.getUsername())));
    } catch (SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/me")
  public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String auth) {
    try { return ResponseEntity.ok(profileWithDaily(service.require(auth))); }
    catch (Exception e) { return ResponseEntity.status(401).body(Map.of("error", e.getMessage())); }
  }

  private Map<String, Object> profileWithDaily(AccountEntity account) {
    return profile(account, dailyBonus.state(account.getId()));
  }

  /** Kept for the admin API, where the current daily state is not needed. */
  static Map<String, Object> profile(AccountEntity account) { return profile(account, null); }

  /** Shared profile mapper for /me and login. */
  static Map<String, Object> profile(AccountEntity account, DailyBonusService.DailyBonusState daily) {
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("id", account.getId());
    profile.put("username", account.getUsername());
    profile.put("role", account.getRole());
    profile.put("blocked", account.isBlocked());
    profile.put("balance", account.getBalance());
    profile.put("points", account.getPoints());
    profile.put("level", account.getLevel());
    profile.put("createdAt", account.getCreatedAt());
    profile.put("puzzlesCompleted", account.getPuzzlesCompleted());
    // Старое поле оставлено, чтобы клиент не ожидал фиксированные +250 за пазл.
    profile.put("puzzleBonus", 0);
    profile.put("dailyRewardMin", DailyBonusService.MIN_REWARD);
    profile.put("dailyRewardMax", DailyBonusService.MAX_REWARD);
    // Лотерея «Закрепи успех»: купленные билеты и счётчик выигрышей для частоты предложения.
    profile.put("lotteryTickets", account.getLotteryTickets());
    profile.put("wins", account.getWins());

    if (daily == null) {
      profile.put("puzzleMask", account.getPuzzleMask());
      profile.put("puzzleFragments", AccountService.PUZZLE_FRAGMENTS);
    } else {
      // При смене даты account.puzzleMask ещё может содержать вчерашнее значение;
      // для UI источником правды является snapshot сегодняшнего daily bonus.
      profile.put("puzzleMask", daily.puzzleMask());
      profile.put("puzzleFragments", daily.total());
      profile.put("dailyBonus", daily.asMap());
    }
    return profile;
  }
}
