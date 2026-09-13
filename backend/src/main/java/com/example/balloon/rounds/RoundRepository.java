package com.example.balloon.rounds;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoundRepository extends JpaRepository<RoundEntity, String> {
  Optional<RoundEntity> findByUsernameAndIdempotencyKey(String username, String idempotencyKey);

  /** Раунды с неподведёнными итогами: после перезапуска их доигрывает или отменяет GameService. */
  List<RoundEntity> findBySettlementCompleteFalseOrderByStartedAtAsc();

  /** Общая лента: завершённые полёты всех игроков без раундов, отменённых перезапуском. */
  @Query("select r from RoundEntity r where r.flightOver = true and r.state <> 'REFUNDED' order by r.startedAt desc")
  List<RoundEntity> findRecentFinished(Pageable page);

  @Query("select r from RoundEntity r where r.username = :username and r.flightOver = true order by r.startedAt desc")
  List<RoundEntity> findRecentFinishedOf(@Param("username") String username, Pageable page);

  @Query("""
      select r.username as username,
             count(r) as rounds,
             sum(case when r.payout > 0 then 1 else 0 end) as cashouts,
             max(r.cashedOutMultiplier) as bestMultiplier,
             max(coalesce(r.endedAt, r.startedAt)) as lastPlayedAt
      from RoundEntity r
      where r.state <> 'REFUNDED'
      group by r.username""")
  List<PlayerRoundStats> statsByPlayer();
}
