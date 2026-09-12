package com.example.balloon.daily;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyBonusRepository extends JpaRepository<DailyBonusEntity, String> {
  Optional<DailyBonusEntity> findByAccountIdAndBonusDate(String accountId, LocalDate bonusDate);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from DailyBonusEntity b where b.account.id = :accountId and b.bonusDate = :bonusDate")
  Optional<DailyBonusEntity> findByAccountIdAndBonusDateForUpdate(@Param("accountId") String accountId,
                                                                    @Param("bonusDate") LocalDate bonusDate);
}
