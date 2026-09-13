package com.example.balloon.daily;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountRepository;
import com.example.balloon.domain.Models.PuzzleAward;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:daily-bonus-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "daily-bonus.zone-id=Europe/Moscow"
})
class DailyBonusServiceIntegrationTest {
  @Autowired private DailyBonusService dailyBonus;
  @Autowired private AccountRepository accounts;

  @Test
  void sixthFragmentOpensOneTicketAndClaimIsIdempotent() {
    AccountEntity account = account();
    for (int i = 0; i < DailyBonusService.PUZZLE_FRAGMENTS; i++) {
      PuzzleAward award = dailyBonus.awardPuzzleFragment(account.getId());
      assertNotNull(award);
      assertEquals(i + 1, award.collected());
    }

    DailyBonusService.DailyBonusState available = dailyBonus.state(account.getId());
    assertEquals(DailyBonusService.PUZZLE_FRAGMENTS, available.progress());
    assertTrue(available.ticket());
    assertTrue(available.eligible());
    assertFalse(available.claimed());
    assertNull(available.reward());

    long balanceBefore = available.balance();
    DailyBonusService.DailyBonusState first = dailyBonus.claim(account.getId(), "daily-claim-1");
    DailyBonusService.DailyBonusState repeated = dailyBonus.claim(account.getId(), "daily-claim-1");

    assertTrue(first.claimed());
    assertFalse(first.eligible());
    assertNotNull(first.reward());
    assertTrue(first.reward() >= DailyBonusService.MIN_REWARD && first.reward() <= DailyBonusService.MAX_REWARD);
    assertEquals(first.reward(), repeated.reward());
    assertEquals(first.balance(), repeated.balance());
    assertEquals(first.balanceAfter(), repeated.balanceAfter());
    assertEquals(first.balance(), first.balanceAfter());
    assertEquals(balanceBefore + first.reward(), accounts.findById(account.getId()).orElseThrow().getBalance());
  }

  @Test
  void roundsAfterACompletedPuzzleDoNotCreateAnotherTicketOrFragment() {
    AccountEntity account = account();
    for (int i = 0; i < DailyBonusService.PUZZLE_FRAGMENTS; i++) dailyBonus.awardPuzzleFragment(account.getId());

    assertNull(dailyBonus.awardPuzzleFragment(account.getId()));
    DailyBonusService.DailyBonusState state = dailyBonus.state(account.getId());
    assertTrue(state.ticket());
    assertTrue(state.eligible());
    assertEquals(DailyBonusService.PUZZLE_FRAGMENTS, state.progress());
  }

  @Test
  void rewardStaysBetweenTenAndOneHundredBonuses() {
    assertEquals(10, DailyBonusService.drawReward(new StubSecureRandom(0)));
    // Заглушка падает на значении вне запрошенной границы, поэтому 100 проверяет и включённый верхний предел.
    assertEquals(100, DailyBonusService.drawReward(new StubSecureRandom(90)));
    java.security.SecureRandom random = new java.security.SecureRandom();
    for (int i = 0; i < 10_000; i++) {
      long reward = DailyBonusService.drawReward(random);
      assertTrue(reward >= 10 && reward <= 100, "награда " + reward);
    }
  }

  private AccountEntity account() {
    String username = "daily" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return accounts.save(new AccountEntity(username, "not-used-in-this-test", "USER"));
  }

  /** A deterministic SecureRandom for testing the exact reward bounds. */
  private static final class StubSecureRandom extends java.security.SecureRandom {
    private final int[] values;
    private int index;

    private StubSecureRandom(int... values) { this.values = values; }

    @Override public int nextInt(int bound) {
      int value = values[Math.min(index++, values.length - 1)];
      if (value < 0 || value >= bound) throw new AssertionError("stub value outside requested bound: " + value + " / " + bound);
      return value;
    }
  }
}
