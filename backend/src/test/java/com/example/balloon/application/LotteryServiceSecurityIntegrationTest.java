package com.example.balloon.application;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.domain.Models.Config;
import com.example.balloon.domain.Models.Round;
import com.example.balloon.domain.Models.RoundState;
import com.example.balloon.domain.Models.Theme;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures a personalized lottery offer cannot be stolen or accepted twice, and arrives once in three wins. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:lottery-security-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class LotteryServiceSecurityIntegrationTest {
  @Autowired private AccountService accounts;
  @Autowired private LotteryService lottery;

  @Test
  void offerBelongsToItsRoundOwnerAndCanOnlyBeAcceptedOnce() {
    AccountEntity owner = player();
    AccountEntity stranger = player();
    Round round = eligibleRound(owner.getUsername());

    assertThrows(NoSuchElementException.class, () -> lottery.offerFor(round, stranger));
    lottery.acceptOffer(owner.getUsername(), round);
    assertTrue(round.offerAccepted);
    assertThrows(IllegalStateException.class, () -> lottery.acceptOffer(owner.getUsername(), round));
  }

  @Test
  void offerComesOnceInThreeWinsAndSmallWinsDoNotBurnIt() {
    String player = player().getUsername();
    int every = LotteryService.OFFER_EVERY_WINS;
    for (int cycle = 0; cycle < 2; cycle++) {
      for (int win = 1; win < every; win++)
        assertFalse(accounts.registerWin(player, 100, 10, every).offerDue(), "выигрыш " + win + " из " + every);
      assertTrue(accounts.registerWin(player, 100, 10, every).offerDue(), "выигрыш " + every + " приносит предложение");
    }

    for (int win = 1; win < every; win++) accounts.registerWin(player, 100, 10, every);
    assertFalse(accounts.registerWin(player, 5, 10, every).offerDue(), "выигрыш меньше порога предложение не получает");
    assertTrue(accounts.registerWin(player, 100, 10, every).offerDue(), "и не сжигает его: предложение переходит на следующий выигрыш");
    assertFalse(accounts.registerWin(player, 100, 10, every).offerDue(), "после предложения счёт начинается заново");
  }

  @Test
  void roundWithoutDueOfferIsDeclined() {
    AccountEntity owner = player();
    Round round = eligibleRound(owner.getUsername());
    round.offerDue = false;

    assertFalse(lottery.showOffer(round, owner).eligible());
    assertThrows(IllegalStateException.class, () -> lottery.acceptOffer(owner.getUsername(), round));
  }

  @Test
  void offerTimerStartsWhenThePlayerSeesTheWindow() {
    AccountEntity owner = player();
    Round round = eligibleRound(owner.getUsername());
    int timeout = round.config.popupTimeout();
    // Экран результата открылся позже, чем прошёл popupTimeout с момента взрыва шара.
    round.endedAt = Instant.now().minusSeconds(timeout + 2);
    assertTrue(lottery.showOffer(round, owner).eligible());
    assertTrue(lottery.offerFor(round, owner).eligible(), "таймер идёт с первого показа окна");

    round.offerShownAt = Instant.now().minusSeconds(timeout + LotteryService.OFFER_NETWORK_GRACE_SECONDS + 1);
    assertFalse(lottery.offerFor(round, owner).eligible(), "после таймера предложение закрыто");

    Round unseen = eligibleRound(owner.getUsername());
    unseen.endedAt = Instant.now().minusSeconds(LotteryService.OFFER_SHOW_WINDOW_SECONDS + 1);
    assertFalse(lottery.showOffer(unseen, owner).eligible(), "предложение, которое так и не показали, не висит бесконечно");
  }

  private Round eligibleRound(String username) {
    Config config = Config.defaults(Theme.RED);
    Round round = new Round(UUID.randomUUID().toString(), username, config, 10, 1,
        "seed", "commitment", config.levels() + 1, 0, config.ladder());
    round.state = RoundState.CASHED_OUT;
    round.flightOver = true;
    round.endedAt = Instant.now();
    round.payout = 100;
    round.offerDue = true;
    return round;
  }

  private AccountEntity player() {
    String username = "lottery" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return accounts.register(username, "password");
  }
}
