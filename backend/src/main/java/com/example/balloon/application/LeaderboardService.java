package com.example.balloon.application;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.rounds.PlayerRoundStats;
import com.example.balloon.rounds.RoundRepository;
import com.example.balloon.tournament.TournamentEntity;
import com.example.balloon.tournament.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Рейтинг и турнир. Очки берутся из аккаунтов, число раундов, выигрышей и лучший коэффициент —
 * из раундов в базе, срок турнира и выплата призов — из таблицы турниров. Поэтому рейтинг
 * одинаков до и после перезапуска сервера.
 */
@Service
public class LeaderboardService {
  public record Entry(String id, String username, long balance, int rank, int level, long points, double multiplier, int rounds, int cashouts, long trend, Instant updatedAt) {}
  public record Tournament(Instant startedAt, Instant endsAt, boolean active, long firstPrize, long secondPrize, long thirdPrize, boolean prizesPaid) {}

  private static final Duration TOURNAMENT_LENGTH = Duration.ofDays(14);
  private static final long[] PRIZES = {1000, 500, 250};

  private final AccountService accounts;
  private final RoundRepository rounds;
  private final TournamentRepository tournaments;
  private final TransactionTemplate transactions;
  /** Места прошлого запроса — только для стрелки изменения позиции, поэтому хранятся в памяти. */
  private volatile Map<String, Integer> previous = Map.of();

  public LeaderboardService(AccountService accounts, RoundRepository rounds, TournamentRepository tournaments,
                            PlatformTransactionManager transactionManager) {
    this.accounts = accounts;
    this.rounds = rounds;
    this.tournaments = tournaments;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public Tournament tournament() {
    TournamentEntity current = currentTournament();
    return new Tournament(current.getStartedAt(), current.getEndsAt(), Instant.now().isBefore(current.getEndsAt()),
        PRIZES[0], PRIZES[1], PRIZES[2], current.isPrizesPaid());
  }

  /** Призы трём лидерам начисляются один раз после окончания турнира: запись турнира блокируется на время выплаты. */
  public void settleTournament() {
    TournamentEntity current = currentTournament();
    if (current.isPrizesPaid() || Instant.now().isBefore(current.getEndsAt())) return;
    transactions.executeWithoutResult(status -> {
      TournamentEntity locked = tournaments.findByIdForUpdate(current.getId()).orElseThrow();
      if (locked.isPrizesPaid()) return;
      List<Entry> top = entries("", "score", 0, PRIZES.length);
      for (int i = 0; i < top.size(); i++)
        accounts.adjust(top.get(i).id(), PRIZES[i], 0, "tournament", "Tournament prize #" + (i + 1));
      locked.markPrizesPaid(Instant.now());
    });
  }

  public synchronized List<Entry> entries(String query, String sort, int offset, int limit) {
    Map<String, PlayerRoundStats> stats = new HashMap<>();
    for (PlayerRoundStats row : rounds.statsByPlayer()) stats.put(row.getUsername(), row);

    String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
    Comparator<Row> order = Comparator.comparingLong(Row::points).reversed().thenComparing(Row::username);
    if ("multiplier".equalsIgnoreCase(sort))
      order = Comparator.comparingDouble(Row::multiplier).reversed().thenComparing(Row::username);
    else if ("activity".equalsIgnoreCase(sort))
      order = Comparator.comparingInt(Row::rounds).reversed().thenComparing(Row::username);

    List<Row> sorted = accounts.all().stream()
        .filter(account -> needle.isBlank() || account.getUsername().toLowerCase(Locale.ROOT).contains(needle))
        .map(account -> Row.of(account, stats.get(account.getUsername())))
        .sorted(order)
        .toList();

    Map<String, Integer> ranks = new HashMap<>();
    for (int i = 0; i < sorted.size(); i++) ranks.put(sorted.get(i).username(), i + 1);
    int from = Math.min(Math.max(0, offset), sorted.size());
    int to = Math.min(sorted.size(), from + Math.max(1, Math.min(limit, 100)));
    List<Entry> out = new ArrayList<>();
    for (int i = from; i < to; i++) {
      Row row = sorted.get(i);
      int rank = i + 1;
      Integer old = previous.get(row.username());
      out.add(new Entry(row.id(), row.username(), row.balance(), rank, Math.max(1, 1 + (int) (row.points() / 250)),
          row.points(), row.multiplier(), row.rounds(), row.cashouts(), old == null ? 0 : old - rank, row.updatedAt()));
    }
    previous = ranks;
    return out;
  }

  /** Текущий турнир из базы; при самом первом запуске создаётся турнир на 14 дней. */
  private synchronized TournamentEntity currentTournament() {
    return tournaments.findTopByOrderByStartedAtDesc().orElseGet(() -> {
      Instant now = Instant.now();
      return tournaments.save(new TournamentEntity(now, now.plus(TOURNAMENT_LENGTH)));
    });
  }

  /** Строка рейтинга: очки и баланс аккаунта плюс итоги его раундов в базе. */
  private record Row(String id, String username, long balance, long points, double multiplier, int rounds, int cashouts, Instant updatedAt) {
    static Row of(AccountEntity account, PlayerRoundStats stats) {
      if (stats == null)
        return new Row(account.getId(), account.getUsername(), account.getBalance(), account.getPoints(), 1.0, 0, 0, account.getUpdatedAt());
      // Лучший коэффициент — зафиксированный выигрыш, а не высота, на которой лопнул проигранный шар.
      double best = stats.getBestMultiplier() == null ? 1.0 : Math.round(stats.getBestMultiplier() * 100.0) / 100.0;
      return new Row(account.getId(), account.getUsername(), account.getBalance(), account.getPoints(), best,
          Math.toIntExact(stats.getRounds()), Math.toIntExact(stats.getCashouts()), stats.getLastPlayedAt());
    }
  }
}
