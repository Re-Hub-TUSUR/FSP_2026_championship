package com.example.balloon.api;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.daily.DailyBonusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API для панели ежедневного пазла и стирания заработанного билета. */
@RestController
@RequestMapping("/api/v1/daily-bonus")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DailyBonusController {
  private final AccountService accounts;
  private final DailyBonusService dailyBonus;

  public DailyBonusController(AccountService accounts, DailyBonusService dailyBonus) {
    this.accounts = accounts;
    this.dailyBonus = dailyBonus;
  }

  /** Body-ключ удобен для клиентов без возможности передать заголовок. Заголовок имеет приоритет. */
  public record ClaimRequest(@Size(max = 128) String idempotencyKey) {}

  @GetMapping
  public ResponseEntity<?> state(@RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      return ResponseEntity.ok(dailyBonus.state(account.getId()).asMap());
    } catch (SecurityException e) {
      return unauthorized(e);
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", message(e)));
    }
  }

  /**
   * Фактическое "стирание" выполняется на сервере. Повтор этого запроса возвращает
   * сохранённую награду и не меняет баланс ещё раз.
   */
  @PostMapping({"/claim", "/scratch"})
  public ResponseEntity<?> claim(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                 @Valid @RequestBody(required = false) ClaimRequest request) {
    try {
      AccountEntity account = accounts.require(auth);
      String key = headerKey != null && !headerKey.isBlank() ? headerKey : request == null ? null : request.idempotencyKey();
      return ResponseEntity.ok(dailyBonus.claim(account.getId(), key).asMap());
    } catch (SecurityException e) {
      return unauthorized(e);
    } catch (IllegalStateException e) {
      return ResponseEntity.status(409).body(Map.of("error", message(e)));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", message(e)));
    } catch (Exception e) {
      return ResponseEntity.internalServerError().body(Map.of("error", message(e)));
    }
  }

  private static ResponseEntity<Map<String, String>> unauthorized(Exception e) {
    return ResponseEntity.status(401).body(Map.of("error", message(e)));
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? "Request failed" : e.getMessage();
  }
}
