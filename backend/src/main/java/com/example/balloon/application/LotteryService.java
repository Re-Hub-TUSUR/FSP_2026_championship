package com.example.balloon.application;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.domain.Models.Round;
import com.example.balloon.domain.Models.RoundState;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Модуль «Закрепи успех»: после удачного раунда игроку предлагается закрепить выигрыш,
 * купив лотерейные билеты за бонусные баллы. Покупка имитируется в рамках прототипа.
 */
@Service
public class LotteryService {
  /** Базовая цена одного билета в бонусных баллах. */
  public static final long TICKET_PRICE = 25;
  /** Скидка на персональное предложение сразу после выигрышного раунда. */
  public static final double OFFER_DISCOUNT = 0.2;
  /** Предложение показывается на каждом N-м выигрыше игрока, чтобы не раздражать повторами. */
  public static final int OFFER_EVERY_WINS = 4;
  private static final int MAX_OFFER_TICKETS = 10;

  private final AccountService accounts;

  public LotteryService(AccountService accounts) { this.accounts = accounts; }

  /** Готовые наборы для вкладки лотереи: чем больше набор, тем выгоднее билет. */
  public record Pack(String id, int tickets, long price, String label) {}

  public List<Pack> packs() {
    return List.of(
        new Pack("single", 1, TICKET_PRICE, "Пробный билет"),
        new Pack("trio", 3, 70, "Три билета"),
        new Pack("five", 5, 110, "Пять билетов"),
        new Pack("ten", 10, Math.round(TICKET_PRICE * 10 * 0.8), "Десять билетов"));
  }

  /**
   * Персональное предложение по итогам раунда: количество билетов растёт вместе с выигрышем,
   * цена ограничена балансом игрока, чтобы предложение всегда оставалось выполнимым.
   */
  public record Offer(boolean eligible, String reason, int tickets, long price, long fullPrice, long payout, int timeoutSeconds) {
    public static Offer declined(String reason, int timeoutSeconds) { return new Offer(false, reason, 0, 0, 0, 0, timeoutSeconds); }
  }

  public Offer offerFor(Round round, AccountEntity account) {
    int timeout = round.config.popupTimeout();
    if (!round.flightOver) return Offer.declined("Раунд ещё не завершён", timeout);
    if (round.state != RoundState.CASHED_OUT && round.state != RoundState.SETTLED)
      return Offer.declined("Предложение доступно только после успешного cashout", timeout);
    if (round.payout < round.config.minWinAmount())
      return Offer.declined("Выигрыш меньше порога " + round.config.minWinAmount() + " ★", timeout);
    if (round.winNumber <= 0 || round.winNumber % OFFER_EVERY_WINS != 0)
      return Offer.declined("Предложение приходит на каждый " + OFFER_EVERY_WINS + "-й выигрыш", timeout);

    int tickets = (int) Math.max(1, Math.min(MAX_OFFER_TICKETS, round.payout / TICKET_PRICE));
    long fullPrice = tickets * TICKET_PRICE;
    long price = Math.round(fullPrice * (1 - OFFER_DISCOUNT));

    // Предложение не должно превышать баланс: уменьшаем набор, пока он не станет доступным.
    while (tickets > 1 && price > account.getBalance()) {
      tickets--;
      fullPrice = tickets * TICKET_PRICE;
      price = Math.round(fullPrice * (1 - OFFER_DISCOUNT));
    }
    if (price > account.getBalance()) return Offer.declined("Недостаточно бонусов для покупки", timeout);

    return new Offer(true, null, tickets, price, fullPrice, round.payout, timeout);
  }

  /** Покупка набора из каталога вкладки. */
  public AccountEntity buyPack(String username, String packId) {
    Pack pack = packs().stream().filter(p -> p.id().equals(packId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Неизвестный набор билетов"));
    return purchase(username, pack.tickets(), pack.price());
  }

  /** Покупка персонального предложения: цена пересчитывается на сервере. */
  public AccountEntity acceptOffer(String username, Round round) {
    AccountEntity account = accounts.byUsername(username);
    Offer offer = offerFor(round, account);
    if (!offer.eligible()) throw new IllegalStateException(offer.reason());
    return purchase(username, offer.tickets(), offer.price());
  }

  private AccountEntity purchase(String username, int tickets, long price) {
    return accounts.buyTickets(username, tickets, price);
  }
}
