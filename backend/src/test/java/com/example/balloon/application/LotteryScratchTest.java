package com.example.balloon.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Стирание купленных билетов не должно в среднем приносить больше, чем стоит самый дешёвый билет. */
class LotteryScratchTest {

  @Test
  void prizeTableIsAProbabilityDistribution() {
    double total = LotteryService.SCRATCH_PRIZES.stream().mapToDouble(LotteryService.ScratchPrize::chance).sum();
    assertEquals(1.0, total, 1e-9);
  }

  @Test
  void averagePrizeIsBelowTheCheapestTicket() {
    double cheapest = LotteryService.TICKET_PRICE * (1 - LotteryService.OFFER_DISCOUNT);
    for (LotteryService.Pack pack : new LotteryService(null).packs())
      cheapest = Math.min(cheapest, (double) pack.price() / pack.tickets());
    assertTrue(LotteryService.expectedScratchPrize() < cheapest,
        "средний приз " + LotteryService.expectedScratchPrize() + " не ниже цены билета " + cheapest);
  }

  @Test
  void drawCoversTheWholeTable() {
    assertEquals(5, LotteryService.drawPrize(0.0));
    assertEquals(200, LotteryService.drawPrize(0.999));
    assertEquals(5, LotteryService.drawPrize(1.0));
  }
}
