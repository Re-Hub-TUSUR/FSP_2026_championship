package com.example.balloon.rounds;

import java.time.Instant;

/** Итоги раундов одного игрока для рейтинга: считаются запросом к базе, а не по раундам в памяти процесса. */
public interface PlayerRoundStats {
  String getUsername();

  long getRounds();

  long getCashouts();

  /** Лучший зафиксированный коэффициент; null — игрок ещё ни разу не забирал выигрыш. */
  Double getBestMultiplier();

  Instant getLastPlayedAt();
}
