package com.example.balloon.api;

import com.example.balloon.application.GameService;
import com.example.balloon.application.LotteryService;
import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Модуль «Закрепи успех»: каталог лотерейных билетов и персональное предложение после раунда. */
@RestController
@RequestMapping("/api/v1/lottery")
@CrossOrigin(origins = "*", maxAge = 3600)
public class LotteryController {
  private final LotteryService lottery;
  private final AccountService accounts;
  private final GameService game;

  public LotteryController(LotteryService lottery, AccountService accounts, GameService game) {
    this.lottery = lottery;
    this.accounts = accounts;
    this.game = game;
  }

  public record BuyRequest(@NotBlank String packId) {}
  public record AcceptRequest(@NotBlank String roundId) {}

  @GetMapping
  public ResponseEntity<?> catalog(@RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("tickets", account.getLotteryTickets());
      body.put("balance", account.getBalance());
      body.put("ticketPrice", LotteryService.TICKET_PRICE);
      body.put("packs", lottery.packs());
      body.put("scratchPrizes", LotteryService.SCRATCH_PRIZES);
      body.put("scratchExpected", Math.round(LotteryService.expectedScratchPrize() * 100.0) / 100.0);
      return ResponseEntity.ok(body);
    } catch (SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/buy")
  public ResponseEntity<?> buy(@Valid @RequestBody BuyRequest request,
                               @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      AccountEntity updated = lottery.buyPack(account.getUsername(), request.packId());
      return ResponseEntity.ok(Map.of("tickets", updated.getLotteryTickets(), "balance", updated.getBalance()));
    } catch (SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  /** Стирание купленного билета: приз определяется и начисляется сервером. */
  @PostMapping("/scratch")
  public ResponseEntity<?> scratch(@RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      return ResponseEntity.ok(lottery.scratch(account.getUsername()));
    } catch (SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/offer/{roundId}")
  public ResponseEntity<?> offer(@PathVariable String roundId,
                                 @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      // GameService hides ownership mismatches as 404 to avoid leaking round
      // existence to another player.
      return ResponseEntity.ok(lottery.offerFor(game.getOwned(roundId, account.getUsername()), account));
    } catch (SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    } catch (NoSuchElementException e) {
      return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/offer/accept")
  public ResponseEntity<?> accept(@Valid @RequestBody AcceptRequest request,
                                  @RequestHeader(value = "Authorization", required = false) String auth) {
    try {
      AccountEntity account = accounts.require(auth);
      AccountEntity updated = lottery.acceptOffer(account.getUsername(), game.getOwned(request.roundId(), account.getUsername()));
      return ResponseEntity.ok(Map.of("tickets", updated.getLotteryTickets(), "balance", updated.getBalance()));
    } catch (SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
    } catch (NoSuchElementException e) {
      return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }
}
