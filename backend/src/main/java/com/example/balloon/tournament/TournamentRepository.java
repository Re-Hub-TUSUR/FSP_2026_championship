package com.example.balloon.tournament;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TournamentRepository extends JpaRepository<TournamentEntity, String> {
  Optional<TournamentEntity> findTopByOrderByStartedAtDesc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from TournamentEntity t where t.id = :id")
  Optional<TournamentEntity> findByIdForUpdate(@Param("id") String id);
}
