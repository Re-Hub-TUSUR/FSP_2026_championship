package com.example.balloon.application;

import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
import com.example.balloon.domain.Models.Round;
import com.example.balloon.domain.Models.RoundState;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
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
  /** Предложение приходит раз в N выигрышей игрока, чтобы не раздражать повторами. */
  public static final int OFFER_EVERY_WINS = 3;
  private static final int MAX_OFFER_TICKETS = 10;
  /** Сколько секунд после окончания полёта предложение ждёт первого показа: экран результата открывается с задержкой. */
  static final int OFFER_SHOW_WINDOW_SECONDS = 60;
  /** Запас на сетевую задержку: нажатие «Купить» в последнюю секунду таймера не должно получать отказ. */
  static final int OFFER_NETWORK_GRACE_SECONDS = 3;

  private final AccountService accounts;
  private final SecureRandom random = new SecureRandom();

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
    if (round == null || account == null) throw new IllegalArgumentException("Раунд и игрок обязательны");
    if (!round.userId.equals(account.getUsername()))
      throw new java.util.NoSuchElementException("Раунд не найден: " + round.id);
    int timeout = round.config.popupTimeout();
    if (round.offerAccepted) return Offer.declined("Предложение уже использовано", timeout);
    if (!round.flightOver) return Offer.declined("Раунд ещё не завершён", timeout);
    if (expired(round, timeout)) return Offer.declined("Срок действия предложения истёк", timeout);
    if (round.state != RoundState.CASHED_OUT && round.state != RoundState.SETTLED)
      return Offer.declined("Предложение доступно только после успешного cashout", timeout);
    if (round.payout < round.config.minWinAmount())
      return Offer.declined("Выигрыш меньше порога " + round.config.minWinAmount() + " ★", timeout);
    if (!round.offerDue)
      return Offer.declined("Предложение приходит " + offerFrequency(), timeout);

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

  /** Предложение для экрана результата: первый показ запускает таймер popupTimeout. */
  public Offer showOffer(Round round, AccountEntity account) {
    Offer offer = offerFor(round, account);
    if (offer.eligible()) synchronized (round) { if (round.offerShownAt == null) round.offerShownAt = Instant.now(); }
    return offer;
  }

  /**
   * Таймер идёт с первого показа окна, а не с конца полёта: экран результата открывается через пару секунд
   * после взрыва, и при отсчёте от конца полёта нажатие «Купить» в последние секунды таймера получало бы отказ.
   */
  private static boolean expired(Round round, int timeout) {
    Instant now = Instant.now();
    if (round.offerShownAt != null) return now.isAfter(round.offerShownAt.plusSeconds(timeout + OFFER_NETWORK_GRACE_SECONDS));
    return round.endedAt != null && now.isAfter(round.endedAt.plusSeconds(OFFER_SHOW_WINDOW_SECONDS));
  }

  /** «раз в 3 выигрыша» с верным склонением — для правил игры и причины отказа. */
  public static String offerFrequency() {
    int n = OFFER_EVERY_WINS;
    if (n <= 1) return "после каждого выигрыша";
    int mod10 = n % 10, mod100 = n % 100;
    String word = mod10 == 1 && mod100 != 11 ? "выигрыш"
        : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "выигрыша" : "выигрышей";
    return "раз в " + n + " " + word;
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
    if (round == null || !round.userId.equals(account.getUsername()))
      throw new java.util.NoSuchElementException("Раунд не найден");
    synchronized (round) {
      if (round.offerAccepted) throw new IllegalStateException("Предложение уже использовано");
      Offer offer = offerFor(round, account);
      if (!offer.eligible()) throw new IllegalStateException(offer.reason());
      AccountEntity updated = purchase(username, offer.tickets(), offer.price());
      // Mark only after the purchase succeeds. A failed debit can be retried,
      // while concurrent successful requests cannot buy the offer twice.
      round.offerAccepted = true;
      return updated;
    }
  }

  /** Приз за стирание купленного билета и его вероятность. */
  public record ScratchPrize(long amount, double chance) {}

  /**
   * Таблица призов. Средний приз 16,75 ★ меньше самой низкой цены билета (20 ★ в наборе из десяти
   * и в предложении после выигрыша), поэтому покупка и стирание билетов не становятся бесконечным заработком.
   */
  public static final List<ScratchPrize> SCRATCH_PRIZES = List.of(
      new ScratchPrize(5, 0.50),
      new ScratchPrize(10, 0.25),
      new ScratchPrize(25, 0.15),
      new ScratchPrize(50, 0.08),
      new ScratchPrize(200, 0.02));

  public record ScratchResult(long prize, int tickets, long balance) {}

  /** Стирает один купленный билет: приз разыгрывается и начисляется на сервере. */
  public ScratchResult scratch(String username) {
    long prize = drawPrize(random.nextDouble());
    AccountEntity updated = accounts.scratchTicket(username, prize);
    return new ScratchResult(prize, updated.getLotteryTickets(), updated.getBalance());
  }

  static long drawPrize(double roll) {
    double accumulated = 0;
    for (ScratchPrize prize : SCRATCH_PRIZES) {
      accumulated += prize.chance();
      if (roll < accumulated) return prize.amount();
    }
    return SCRATCH_PRIZES.get(0).amount();
  }

  public static double expectedScratchPrize() {
    return SCRATCH_PRIZES.stream().mapToDouble(prize -> prize.amount() * prize.chance()).sum();
  }

  private AccountEntity purchase(String username, int tickets, long price) {
    return accounts.buyTickets(username, tickets, price);
  }
}
