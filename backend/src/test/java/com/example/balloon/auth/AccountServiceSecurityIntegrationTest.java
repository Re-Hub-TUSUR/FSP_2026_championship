package com.example.balloon.auth;

import com.example.balloon.daily.DailyBonusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for account-level authorization, persistent sessions and the server-side top-up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:account-security-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "daily-bonus.zone-id=Europe/Moscow"
})
class AccountServiceSecurityIntegrationTest {
  @Autowired private AccountService accounts;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AuditRepository auditRepository;
  @Autowired private DailyBonusService dailyBonus;
  @Autowired private SessionRepository sessions;

  @Test
  void aTokenStopsWorkingWhenItsAccountIsBlocked() {
    AccountEntity account = register();
    String token = accounts.login(account.getUsername(), "password");

    accounts.setBlocked(account.getId(), true, "admin", "test");

    assertThrows(SecurityException.class, () -> accounts.require("Bearer " + token));
    assertTrue(sessions.findByTokenHash(AccountService.tokenHash(token)).isEmpty(), "блокировка удаляет сессии аккаунта из базы");
    accounts.setBlocked(account.getId(), false, "admin", "test");
    assertThrows(SecurityException.class, () -> accounts.require("Bearer " + token), "после разблокировки нужен новый вход");
  }

  @Test
  void tokenSurvivesRestartAndOnlyItsHashIsStored() {
    AccountEntity account = register();
    String token = accounts.login(account.getUsername(), "password");

    assertEquals(account.getId(), restart().require("Bearer " + token).getId(), "токен работает после перезапуска");

    SessionEntity session = sessions.findByTokenHash(AccountService.tokenHash(token)).orElseThrow();
    assertEquals(account.getId(), session.getAccountId());
    assertTrue(sessions.findAll().stream().noneMatch(stored -> stored.getTokenHash().contains(token)), "сам токен в базе не хранится");
  }

  @Test
  void logoutRevokesOnlyThatTokenEvenAfterRestart() {
    AccountEntity account = register();
    String token = accounts.login(account.getUsername(), "password");
    String otherDevice = accounts.login(account.getUsername(), "password");

    accounts.logout("Bearer " + token);

    AccountService restarted = restart();
    assertThrows(SecurityException.class, () -> restarted.require("Bearer " + token));
    assertEquals(account.getId(), restarted.require("Bearer " + otherDevice).getId(), "выход на одном устройстве не трогает другие");
  }

  @Test
  void expiredTokenIsRejectedAndExpiredSessionsArePurged() {
    AccountEntity account = register();
    String expired = accounts.login(account.getUsername(), "password");
    String abandoned = accounts.login(account.getUsername(), "password");
    String active = accounts.login(account.getUsername(), "password");
    expire(expired);
    expire(abandoned);

    assertThrows(SecurityException.class, () -> accounts.require("Bearer " + expired));
    assertTrue(sessions.findByTokenHash(AccountService.tokenHash(expired)).isEmpty(), "истёкшая сессия удаляется при обращении");

    assertTrue(accounts.purgeExpiredSessions() >= 1, "периодическая очистка удаляет истёкшие сессии");
    assertTrue(sessions.findByTokenHash(AccountService.tokenHash(abandoned)).isEmpty());
    assertEquals(account.getId(), accounts.require("Bearer " + active).getId(), "действующая сессия не затронута");
  }

  @Test
  void activeSessionSlidesItsExpiry() {
    AccountEntity account = register();
    String token = accounts.login(account.getUsername(), "password");
    SessionEntity session = sessions.findByTokenHash(AccountService.tokenHash(token)).orElseThrow();
    sessions.touch(session.getId(), Instant.now().minus(Duration.ofHours(2)), Instant.now().plus(Duration.ofMinutes(5)));

    accounts.require("Bearer " + token);

    Instant expiresAt = sessions.findById(session.getId()).orElseThrow().getExpiresAt();
    assertTrue(expiresAt.isAfter(Instant.now().plus(AccountService.DEFAULT_SESSION_TTL).minus(Duration.ofMinutes(1))),
        "срок продлён на полный период от последнего обращения: " + expiresAt);
  }

  @Test
  void topUpIsAuthenticatedServerSideAndLimitedToOnePerDay() {
    AccountEntity account = register();
    String token = accounts.login(account.getUsername(), "password");

    AccountEntity first = accounts.topUp(accounts.require("Bearer " + token).getUsername());

    assertEquals(1_000 + AccountService.TOPUP_AMOUNT, first.getBalance());
    assertThrows(IllegalStateException.class, () -> accounts.topUp(account.getUsername()));
  }

  /** Новый экземпляр сервиса поверх той же базы — так выглядит перезапуск backend. */
  private AccountService restart() {
    return new AccountService(accountRepository, auditRepository, dailyBonus, sessions);
  }

  private void expire(String token) {
    SessionEntity session = sessions.findByTokenHash(AccountService.tokenHash(token)).orElseThrow();
    Instant past = Instant.now().minus(Duration.ofDays(1));
    sessions.touch(session.getId(), past.minus(AccountService.DEFAULT_SESSION_TTL), past);
  }

  private AccountEntity register() {
    String username = "acct" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return accounts.register(username, "password");
  }
}
