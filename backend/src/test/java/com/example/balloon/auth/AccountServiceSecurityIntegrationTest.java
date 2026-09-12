package com.example.balloon.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression tests for account-level authorization and the server-side top-up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:account-security-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "daily-bonus.zone-id=Europe/Moscow"
})
class AccountServiceSecurityIntegrationTest {
  @Autowired private AccountService accounts;

  @Test
  void aTokenStopsWorkingWhenItsAccountIsBlocked() {
    AccountEntity account = register();
    String token = accounts.login(account.getUsername(), "password");

    accounts.setBlocked(account.getId(), true, "admin", "test");

    assertThrows(SecurityException.class, () -> accounts.require("Bearer " + token));
  }

  @Test
  void topUpIsAuthenticatedServerSideAndLimitedToOnePerDay() {
    AccountEntity account = register();
    String token = accounts.login(account.getUsername(), "password");

    AccountEntity first = accounts.topUp(accounts.require("Bearer " + token).getUsername());

    assertEquals(1_000 + AccountService.TOPUP_AMOUNT, first.getBalance());
    assertThrows(IllegalStateException.class, () -> accounts.topUp(account.getUsername()));
  }

  private AccountEntity register() {
    String username = "acct" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return accounts.register(username, "password");
  }
}
