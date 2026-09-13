package com.example.balloon.api;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.daily.LoginRewardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/login-reward")
@CrossOrigin(origins = "*", maxAge = 3600)
public class LoginRewardController {
  private final AccountService accounts;
  private final LoginRewardService rewards;
  public LoginRewardController(AccountService accounts, LoginRewardService rewards) { this.accounts = accounts; this.rewards = rewards; }

  @GetMapping public ResponseEntity<?> state(@RequestHeader(value="Authorization", required=false) String auth) {
    try { AccountEntity account = accounts.require(auth); return ResponseEntity.ok(rewards.state(account.getId())); }
    catch (SecurityException e) { return ResponseEntity.status(401).body(Map.of("error", message(e))); }
    catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", message(e))); }
  }
  @PostMapping("/claim") public ResponseEntity<?> claim(@RequestHeader(value="Authorization", required=false) String auth) {
    try { AccountEntity account = accounts.require(auth); return ResponseEntity.ok(rewards.claim(account.getId())); }
    catch (SecurityException e) { return ResponseEntity.status(401).body(Map.of("error", message(e))); }
    catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", message(e))); }
    catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", message(e))); }
  }
  private static String message(Exception e) { return e.getMessage() == null ? "Request failed" : e.getMessage(); }
}
