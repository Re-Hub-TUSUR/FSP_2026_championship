package com.example.balloon.daily;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/** Серверный семидневный календарь входа. Граница суток всегда московская. */
@Service
public class LoginRewardService {
  private static final long[] COINS = {10, 20, 30, 0, 40, 0, 0};
  private static final int[] PUZZLES = {0, 0, 0, 2, 0, 2, 0};
  private final AccountRepository accounts;
  private final DailyBonusRepository dailyBonuses;
  private final ZoneId zoneId;

  public LoginRewardService(AccountRepository accounts, DailyBonusRepository dailyBonuses,
      @Value("${daily-bonus.zone-id:Europe/Moscow}") String zoneId) {
    this.accounts = accounts;
    this.dailyBonuses = dailyBonuses;
    this.zoneId = ZoneId.of(zoneId);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> state(String accountId) {
    AccountEntity account = accounts.findById(accountId).orElseThrow(() -> new NoSuchElementException("User not found"));
    return snapshot(account, false, 0, 0, 0);
  }

  @Transactional
  public Map<String, Object> claim(String accountId) {
    AccountEntity account = accounts.findByIdForUpdate(accountId).orElseThrow(() -> new NoSuchElementException("User not found"));
    if (account.isBlocked()) throw new SecurityException("Account is blocked");
    LocalDate today = LocalDate.now(zoneId);
    if (today.equals(account.getLastLoginRewardDate())) throw new IllegalStateException("Сегодняшняя награда уже получена");

    LocalDate previous = account.getLastLoginRewardDate();
    int day = previous != null && previous.plusDays(1).equals(today)
        ? account.getLoginRewardDay() % 7 + 1 : 1;
    long coins = COINS[day - 1];
    int puzzles = PUZZLES[day - 1];
    int tickets = day == 7 ? 1 : 0;
    if (coins > 0) account.setBalance(Math.addExact(account.getBalance(), coins));
    if (tickets > 0) account.setLotteryTickets(account.getLotteryTickets() + tickets);
    if (puzzles > 0) addPuzzleFragments(account, today, puzzles);
    account.setLoginRewardDay(day);
    account.setLastLoginRewardDate(today);
    return snapshot(account, true, coins, puzzles, tickets);
  }

  private void addPuzzleFragments(AccountEntity account, LocalDate today, int count) {
    DailyBonusEntity daily = dailyBonuses.findByAccountIdAndBonusDateForUpdate(account.getId(), today)
        .orElseGet(() -> dailyBonuses.save(new DailyBonusEntity(account, today)));
    int mask = daily.getPuzzleMask();
    int before = Integer.bitCount(mask);
    for (int bit = 0; bit < DailyBonusService.PUZZLE_FRAGMENTS && Integer.bitCount(mask) < before + count; bit++)
      mask |= 1 << bit;
    daily.setPuzzleMask(mask);
    if (Integer.bitCount(mask) == DailyBonusService.PUZZLE_FRAGMENTS && !daily.isTicketIssued()) {
      daily.issueTicket();
      account.setPuzzlesCompleted(account.getPuzzlesCompleted() + 1);
    }
    account.setPuzzleMask(mask);
  }

  private Map<String, Object> snapshot(AccountEntity account, boolean justClaimed, long coins, int puzzles, int tickets) {
    LocalDate today = LocalDate.now(zoneId);
    boolean claimedToday = today.equals(account.getLastLoginRewardDate());
    int completedDay = account.getLoginRewardDay();
    int currentDay = claimedToday ? completedDay :
        account.getLastLoginRewardDate() != null && account.getLastLoginRewardDate().plusDays(1).equals(today)
            ? completedDay % 7 + 1 : 1;
    Instant nextResetAt = today.plusDays(1).atStartOfDay(zoneId).toInstant();
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("currentDay", currentDay);
    value.put("completedDay", completedDay);
    value.put("claimedToday", claimedToday);
    value.put("claimable", !claimedToday);
    value.put("nextResetAt", nextResetAt);
    value.put("balance", account.getBalance());
    value.put("puzzleMask", account.getPuzzleMask());
    value.put("lotteryTickets", account.getLotteryTickets());
    value.put("justClaimed", justClaimed);
    value.put("awardedCoins", coins);
    value.put("awardedPuzzles", puzzles);
    value.put("awardedTickets", tickets);
    return value;
  }
}
