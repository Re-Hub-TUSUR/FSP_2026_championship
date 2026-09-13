package com.example.balloon.daily;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountRepository;
import com.example.balloon.domain.Models.PuzzleAward;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Серверная механика ежедневного пазла и лотерейного билета.
 *
 * <p>Один билет можно получить только один раз за календарный день в настроенной
 * временной зоне. Награда выбирается и начисляется в одной транзакции; браузер
 * получает только уже зафиксированный результат стирания.</p>
 */
@Service
public class DailyBonusService {
  public static final int PUZZLE_FRAGMENTS = 6;
  /** Награда за ежедневный билет: целое число бонусов от MIN_REWARD до MAX_REWARD включительно, все суммы равновероятны. */
  public static final long MIN_REWARD = 10;
  public static final long MAX_REWARD = 100;

  public enum TicketState { LOCKED, AVAILABLE, SCRATCHED }

  /** Полное состояние текущего календарного дня, которое отдаётся клиенту. */
  public record DailyBonusState(
      LocalDate date,
      int puzzleMask,
      int progress,
      int total,
      int puzzleFragments,
      boolean completed,
      boolean ticket,
      TicketState ticketState,
      boolean eligible,
      boolean claimed,
      Long reward,
      Instant claimedAt,
      Instant nextResetAt,
      long balance,
      long balanceAfter) {

    public Map<String, Object> asMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("date", date);
      // Короткие алиасы полезны для клиентов, созданных до финализации контракта.
      result.put("day", date);
      result.put("puzzleMask", puzzleMask);
      result.put("progress", progress);
      result.put("collected", progress);
      result.put("total", total);
      result.put("puzzleFragments", puzzleFragments);
      result.put("completed", completed);
      result.put("ticket", ticket);
      result.put("ticketState", ticketState);
      result.put("eligible", eligible);
      result.put("ticketAvailable", eligible);
      result.put("claimed", claimed);
      result.put("reward", reward);
      result.put("claimedAt", claimedAt);
      result.put("nextResetAt", nextResetAt);
      result.put("balance", balance);
      result.put("balanceAfter", balanceAfter);
      return result;
    }
  }

  private final AccountRepository accounts;
  private final DailyBonusRepository dailyBonuses;
  private final SecureRandom random = new SecureRandom();
  private final ZoneId zoneId;

  public DailyBonusService(AccountRepository accounts,
                           DailyBonusRepository dailyBonuses,
                           @Value("${daily-bonus.zone-id:Europe/Moscow}") String zoneId) {
    this.accounts = accounts;
    this.dailyBonuses = dailyBonuses;
    this.zoneId = ZoneId.of(zoneId);
  }

  /** Возвращает сегодняшнее состояние. Новый день всегда начинается с пустого пазла. */
  @Transactional(readOnly = true)
  public DailyBonusState state(String accountId) {
    AccountEntity account = accounts.findById(accountId)
        .orElseThrow(() -> new NoSuchElementException("User not found"));
    LocalDate date = today();
    return snapshot(account, dailyBonuses.findByAccountIdAndBonusDate(accountId, date).orElse(null), date);
  }

  /**
   * Выдаёт один случайный недостающий фрагмент за завершённый раунд.
   * После 6/6 последующие раунды в тот же день не меняют билет и возвращают null.
   */
  @Transactional
  public PuzzleAward awardPuzzleFragment(String accountId) {
    AccountEntity account = lockAccount(accountId);
    LocalDate date = today();
    DailyBonusEntity daily = dailyBonuses.findByAccountIdAndBonusDateForUpdate(accountId, date)
        .orElseGet(() -> dailyBonuses.save(new DailyBonusEntity(account, date)));

    if (daily.isTicketIssued()) {
      // Не допускаем nextInt(0) и не выдаём второй билет в этот же день.
      account.setPuzzleMask(daily.getPuzzleMask());
      return null;
    }

    List<Integer> missing = new ArrayList<>();
    for (int fragment = 0; fragment < PUZZLE_FRAGMENTS; fragment++) {
      if ((daily.getPuzzleMask() & (1 << fragment)) == 0) missing.add(fragment);
    }
    // Защитный путь на случай старой/повреждённой записи без флага ticketIssued.
    if (missing.isEmpty()) {
      daily.issueTicket();
      account.setPuzzleMask(daily.getPuzzleMask());
      account.setPuzzlesCompleted(account.getPuzzlesCompleted() + 1);
      return new PuzzleAward(PUZZLE_FRAGMENTS - 1, PUZZLE_FRAGMENTS, PUZZLE_FRAGMENTS, true, 0, true);
    }

    int fragment = missing.get(random.nextInt(missing.size()));
    int mask = daily.getPuzzleMask() | (1 << fragment);
    int collected = Integer.bitCount(mask);
    boolean completed = collected == PUZZLE_FRAGMENTS;
    daily.setPuzzleMask(mask);
    if (completed) {
      // Полный пазл открывает билет. Фиксированного бонуса больше нет: он определяется при claim.
      daily.issueTicket();
      account.setPuzzlesCompleted(account.getPuzzlesCompleted() + 1);
    }
    // Поле сохранено также в account для обратной совместимости старых клиентов /me.
    account.setPuzzleMask(mask);
    return new PuzzleAward(fragment, collected, PUZZLE_FRAGMENTS, completed, 0, completed);
  }

  /**
   * Атомарно стирает сегодняшний билет. Повторный вызов (с тем же или без ключа)
   * возвращает уже сохранённую награду и никогда не начисляет баланс повторно.
   */
  @Transactional
  public DailyBonusState claim(String accountId, String idempotencyKey) {
    AccountEntity account = lockAccount(accountId);
    LocalDate date = today();
    DailyBonusEntity daily = dailyBonuses.findByAccountIdAndBonusDateForUpdate(accountId, date)
        .orElseThrow(() -> new IllegalStateException("Сначала соберите все фрагменты пазла за сегодня"));
    if (!daily.isTicketIssued()) {
      throw new IllegalStateException("Сначала соберите все фрагменты пазла за сегодня");
    }

    if (!daily.isClaimed()) {
      long reward = drawReward(random);
      long balanceAfter = Math.addExact(account.getBalance(), reward);
      account.setBalance(balanceAfter);
      daily.claim(reward, normalizeIdempotencyKey(idempotencyKey), balanceAfter);
    }
    return snapshot(account, daily, date);
  }

  /** Равновероятная целая награда от MIN_REWARD до MAX_REWARD включительно — от 10 до 100 бонусов. */
  static long drawReward(SecureRandom random) {
    return MIN_REWARD + random.nextInt((int) (MAX_REWARD - MIN_REWARD + 1));
  }

  private AccountEntity lockAccount(String accountId) {
    AccountEntity account = accounts.findByIdForUpdate(accountId)
        .orElseThrow(() -> new NoSuchElementException("User not found"));
    if (account.isBlocked()) throw new SecurityException("Account is blocked");
    return account;
  }

  private DailyBonusState snapshot(AccountEntity account, DailyBonusEntity daily, LocalDate currentDate) {
    if (daily == null) {
      return new DailyBonusState(currentDate, 0, 0, PUZZLE_FRAGMENTS, PUZZLE_FRAGMENTS,
          false, false, TicketState.LOCKED, false, false, null, null, nextResetAt(currentDate), account.getBalance(), account.getBalance());
    }
    int progress = Integer.bitCount(daily.getPuzzleMask());
    boolean ticket = daily.isTicketIssued();
    boolean completed = progress == PUZZLE_FRAGMENTS;
    boolean claimed = daily.isClaimed();
    TicketState ticketState = !ticket ? TicketState.LOCKED : claimed ? TicketState.SCRATCHED : TicketState.AVAILABLE;
    long balanceAfter = daily.getBalanceAfter() == null ? account.getBalance() : daily.getBalanceAfter();
    return new DailyBonusState(daily.getBonusDate(), daily.getPuzzleMask(), progress,
        PUZZLE_FRAGMENTS, PUZZLE_FRAGMENTS, completed, ticket, ticketState,
        ticket && !claimed, claimed, daily.getReward(), daily.getClaimedAt(), nextResetAt(currentDate), account.getBalance(), balanceAfter);
  }

  private LocalDate today() { return LocalDate.now(zoneId); }
  private Instant nextResetAt(LocalDate date) { return date.plusDays(1).atStartOfDay(zoneId).toInstant(); }

  private static String normalizeIdempotencyKey(String value) {
    if (value == null || value.isBlank()) return null;
    String key = value.trim();
    if (key.length() > 128) throw new IllegalArgumentException("Idempotency-Key must be at most 128 characters");
    return key;
  }
}
