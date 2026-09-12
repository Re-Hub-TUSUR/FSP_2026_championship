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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures a personalized lottery offer cannot be stolen or accepted twice. */
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

  private Round eligibleRound(String username) {
    Config config = Config.defaults(Theme.RED);
    Round round = new Round(UUID.randomUUID().toString(), username, config, 10, 1,
        "seed", "commitment", config.levels() + 1, 0, config.ladder());
    round.state = RoundState.CASHED_OUT;
    round.flightOver = true;
    round.endedAt = Instant.now();
    round.payout = 100;
    round.winNumber = LotteryService.OFFER_EVERY_WINS;
    return round;
  }

  private AccountEntity player() {
    String username = "lottery" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return accounts.register(username, "password");
  }
}
