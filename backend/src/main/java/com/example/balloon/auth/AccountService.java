package com.example.balloon.auth;

import com.example.balloon.domain.Models.PuzzleAward;
import com.example.balloon.daily.DailyBonusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@Service
public class AccountService implements CommandLineRunner {
    /** Требования к паролю, общие для API-валидации и проверки в сервисе. */
    public static final String PASSWORD_REQUIREMENTS = "Пароль должен содержать от 6 до 128 символов";
    /** Количество фрагментов дневного пазла. Награда определяется билетом, а не фиксированной суммой. */
    public static final int PUZZLE_FRAGMENTS = DailyBonusService.PUZZLE_FRAGMENTS;
    /**
     * @deprecated оставлено только для бинарной/исходной совместимости старых клиентов;
     * сбор пазла больше не начисляет эту сумму — награда приходит через claim билета.
     */
    @Deprecated public static final long PUZZLE_BONUS = 250;
    /** Fixed amount used by the authenticated demo top-up button. */
    public static final long TOPUP_AMOUNT = 500;
    /** Сессия входа действует столько с последнего обращения. */
    public static final Duration DEFAULT_SESSION_TTL = Duration.ofDays(30);
    /** Срок продлевается не чаще раза в час: экран полёта опрашивает сервер дважды в секунду, запись на каждый запрос была бы лишней. */
    static final Duration SESSION_TOUCH_INTERVAL = Duration.ofHours(1);

    private final AccountRepository accounts; private final AuditRepository audits; private final DailyBonusService dailyBonus; private final ZoneId topUpZoneId;
    /** Сессии входа хранятся в базе и переживают перезапуск сервера; сам токен туда не попадает, только его SHA-256. */
    private final SessionRepository sessions; private final Duration sessionTtl;
    private final SecureRandom random = new SecureRandom();
    @Autowired
    public AccountService(AccountRepository accounts, AuditRepository audits, DailyBonusService dailyBonus, SessionRepository sessions,
                          @Value("${daily-bonus.zone-id:Europe/Moscow}") String topUpZoneId,
                          @Value("${auth.session-ttl:P30D}") Duration sessionTtl){
        if(sessionTtl==null||sessionTtl.isZero()||sessionTtl.isNegative()) throw new IllegalArgumentException("auth.session-ttl must be positive");
        this.accounts=accounts;this.audits=audits;this.dailyBonus=dailyBonus;this.sessions=sessions;this.topUpZoneId=ZoneId.of(topUpZoneId);this.sessionTtl=sessionTtl;
    }
    /** Source-compatible constructor for lightweight unit tests and embedders. */
    public AccountService(AccountRepository accounts, AuditRepository audits, DailyBonusService dailyBonus, SessionRepository sessions){
        this(accounts, audits, dailyBonus, sessions, "Europe/Moscow", DEFAULT_SESSION_TTL);
    }
    @Override @Transactional public void run(String... args){
        if(!accounts.existsByUsernameIgnoreCase("demo")) accounts.save(new AccountEntity("demo", hash("demo123"), "USER"));
        if(!accounts.existsByUsernameIgnoreCase("admin")) accounts.save(new AccountEntity("admin", hash("admin123"), "ADMIN"));
        purgeExpiredSessions();
    }
    public AccountEntity register(String username,String password){
        username=clean(username); validatePassword(password);
        if(accounts.existsByUsernameIgnoreCase(username)) throw new IllegalArgumentException("Username is already taken");
        return accounts.save(new AccountEntity(username,hash(password),"USER"));
    }
    public String login(String username,String password){
        AccountEntity a=accounts.findByUsernameIgnoreCase(clean(username)).orElseThrow(()->new IllegalArgumentException("Invalid credentials"));
        if(a.isBlocked()) throw new IllegalStateException("Account is blocked");
        if(!verify(password,a.getPasswordHash())) throw new IllegalArgumentException("Invalid credentials");
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        // В базу попадает только SHA-256 токена: содержимое таблицы sessions не позволяет войти в аккаунт.
        Instant now=Instant.now();
        sessions.save(new SessionEntity(a.getId(),tokenHash(token),now,now.plus(sessionTtl)));
        return token;
    }
    public void logout(String token){
        String normalized=tokenFromAuth(token);
        if(normalized!=null)sessions.deleteByTokenHash(tokenHash(normalized));
    }
    public AccountEntity current(String auth){
        String token=tokenFromAuth(auth);
        if(token==null) return null;
        SessionEntity session=sessions.findByTokenHash(tokenHash(token)).orElse(null);
        if(session==null) return null;
        Instant now=Instant.now();
        // Истёкший токен не принимается, а его запись удаляется сразу, не дожидаясь периодической очистки.
        if(!session.getExpiresAt().isAfter(now)) {
            sessions.deleteByTokenHash(session.getTokenHash());
            return null;
        }
        AccountEntity account=accounts.findById(session.getAccountId()).orElse(null);
        // A token issued before an administrator blocked the account must not
        // remain usable.  Remove it eagerly so every subsequent request is
        // rejected, including requests racing with the block operation.
        if(account==null || account.isBlocked()) {
            sessions.deleteByTokenHash(session.getTokenHash());
            return null;
        }
        // Скользящий срок: активная сессия продлевается на полный срок от последнего обращения.
        if(session.getLastUsedAt().isBefore(now.minus(SESSION_TOUCH_INTERVAL)))
            sessions.touch(session.getId(),now,now.plus(sessionTtl));
        return account;
    }
    /** Удаляет истёкшие сессии при старте и раз в час; истёкший токен не принимается и до очистки. */
    @Scheduled(initialDelayString="${auth.session-cleanup-ms:3600000}",fixedDelayString="${auth.session-cleanup-ms:3600000}")
    public int purgeExpiredSessions(){
        return sessions.deleteExpired(Instant.now());
    }
    public AccountEntity require(String auth){
        AccountEntity a=current(auth);
        if(a==null)throw new SecurityException("Authentication required");
        // Keep an explicit check here as well: the account can be blocked
        // between current() and the operation that follows it.
        if(a.isBlocked()) throw new SecurityException("Account is blocked");
        return a;
    }
    public AccountEntity requireAdmin(String auth){AccountEntity a=require(auth);if(!"ADMIN".equals(a.getRole()))throw new SecurityException("Admin role required");return a;}
    public List<AccountEntity> all(){return accounts.findAllByOrderByPointsDesc();}
    public AccountEntity byUsername(String username){return accounts.findByUsernameIgnoreCase(clean(username)).orElseThrow(()->new NoSuchElementException("User not found"));}
    @Transactional public AccountEntity debit(String username,long amount){
        if(amount<0) throw new IllegalArgumentException("Amount must be positive");
        AccountEntity a=byUsernameForUpdate(username);
        if(a.isBlocked()) throw new SecurityException("Account is blocked");
        if(a.getBalance()<amount) throw new IllegalStateException("Insufficient balance");
        a.setBalance(a.getBalance()-amount); return accounts.save(a);
    }
    /**
     * Server-side fixed top-up used by the demo header action.  It is an
     * authenticated account operation (never a client-local balance change),
     * uses the same row lock as bets and records an audit event.
     */
    @Transactional public AccountEntity topUp(String username){
        AccountEntity a=byUsernameForUpdate(username);
        if(a.isBlocked()) throw new SecurityException("Account is blocked");
        LocalDate today=LocalDate.now(topUpZoneId);
        if(today.equals(a.getLastTopUpDate())) throw new IllegalStateException("Пополнение уже доступно на следующий день");
        a.setBalance(Math.addExact(a.getBalance(), TOPUP_AMOUNT));
        a.setLastTopUpDate(today);
        AccountEntity updated=accounts.save(a);
        audit(username,"TOPUP", "+"+TOPUP_AMOUNT+" бонусов");
        return updated;
    }
    @Transactional public AccountEntity credit(String username,long amount){
        if(amount<0) throw new IllegalArgumentException("Amount must be positive");
        AccountEntity a=byUsernameForUpdate(username); a.setBalance(Math.addExact(a.getBalance(),amount)); return accounts.save(a);
    }
    @Transactional public AccountEntity addPoints(String username,long amount){
        AccountEntity a=byUsernameForUpdate(username); a.setPoints(Math.max(0,Math.addExact(a.getPoints(),amount))); return accounts.save(a);
    }

    /**
     * Награда за раунд: случайный недостающий фрагмент сегодняшнего пазла. Когда собраны
     * все фрагменты, открывается один лотерейный билет; баланс меняется только при его claim.
     */
    public PuzzleAward awardPuzzleFragment(String username){
        return dailyBonus.awardPuzzleFragment(byUsername(username).getId());
    }
    /** Учтённый выигрыш: его порядковый номер и положено ли за него предложение «Закрепи успех». */
    public record WinRecord(int wins, boolean offerDue) {}

    /**
     * Отмечает выигранный раунд. Предложение положено, когда после прошлого предложения набралось
     * everyWins выигрышей, а текущий не меньше порога. Выигрыш меньше порога предложение не сжигает:
     * оно переходит на ближайший подходящий.
     */
    @Transactional public WinRecord registerWin(String username,long payout,long minOfferPayout,int everyWins){
        // Multiple rounds may settle concurrently. Lock the row so the win
        // counters cannot lose an increment and an offer is granted only once.
        AccountEntity a=byUsernameForUpdate(username);
        a.setWins(a.getWins()+1);
        int pending=a.getWinsSinceOffer()+1;
        boolean offerDue=pending>=Math.max(1,everyWins) && payout>=minOfferPayout;
        a.setWinsSinceOffer(offerDue?0:pending);
        return new WinRecord(accounts.save(a).getWins(),offerDue);
    }

    /** Покупка лотерейных билетов: списание бонусов и начисление билетов одной транзакцией. */
    @Transactional public AccountEntity buyTickets(String username,int tickets,long price){
        if(tickets<1) throw new IllegalArgumentException("Нужен хотя бы один билет");
        AccountEntity a=debit(username,price);
        a.setLotteryTickets(a.getLotteryTickets()+tickets);
        return accounts.save(a);
    }
    /**
     * Стирание купленного билета: билет списывается, приз начисляется на баланс одной
     * транзакцией под блокировкой строки, поэтому без билета приз получить нельзя.
     */
    @Transactional public AccountEntity scratchTicket(String username,long prize){
        if(prize<0) throw new IllegalArgumentException("Prize must be positive");
        AccountEntity a=byUsernameForUpdate(username);
        if(a.isBlocked()) throw new SecurityException("Account is blocked");
        if(a.getLotteryTickets()<1) throw new IllegalStateException("Нет билетов для стирания");
        a.setLotteryTickets(a.getLotteryTickets()-1);
        a.setBalance(Math.addExact(a.getBalance(),prize));
        AccountEntity updated=accounts.save(a);
        audit(username,"TICKET_SCRATCH","+"+prize+" бонусов");
        return updated;
    }
    public AccountEntity byId(String id){return accounts.findById(id).orElseThrow(()->new NoSuchElementException("User not found"));}
    /** Блокировка отзывает все сессии игрока: после разблокировки нужно войти заново. */
    @Transactional public AccountEntity setBlocked(String id,boolean blocked,String actor,String reason){AccountEntity a=byIdForUpdate(id);a.setBlocked(blocked);accounts.save(a);if(blocked)sessions.deleteByAccountId(a.getId());audit(actor,blocked?"USER_BLOCK":"USER_UNBLOCK",a.getUsername()+": "+reason);return a;}
    @Transactional public AccountEntity setRole(String id,String role,String actor,String reason){if(!Set.of("USER","ADMIN").contains(role))throw new IllegalArgumentException("Role must be USER or ADMIN");AccountEntity a=byIdForUpdate(id);a.setRole(role);accounts.save(a);audit(actor,"ROLE_CHANGE",a.getUsername()+" -> "+role+": "+reason);return a;}
    @Transactional public AccountEntity adjust(String id,long balanceDelta,long pointsDelta,String actor,String reason){AccountEntity a=byIdForUpdate(id);a.setBalance(Math.max(0,a.getBalance()+balanceDelta));a.setPoints(Math.max(0,a.getPoints()+pointsDelta));accounts.save(a);audit(actor,"MANUAL_ADJUSTMENT",a.getUsername()+": "+reason+" [balance="+balanceDelta+", points="+pointsDelta+"]");return a;}
    public List<AuditEntity> audit(){return audits.findTop100ByOrderByCreatedAtDesc();}
    public void audit(String actor,String action,String details){audits.save(new AuditEntity(actor,action,details));}
    private static String clean(String s){if(s==null||!s.matches("[A-Za-z0-9_\\-]{3,40}"))throw new IllegalArgumentException("Username must be 3-40 latin characters");return s.toLowerCase(Locale.ROOT);}
    /** Extracts a bearer token without accepting an empty/whitespace token. */
    private static String tokenFromAuth(String auth){
        if(auth==null) return null;
        String value=auth.trim();
        if(value.isEmpty()) return null;
        if(value.regionMatches(true,0,"Bearer ",0,7)) value=value.substring(7).trim();
        return value.isEmpty()?null:value;
    }
    /** SHA-256 токена в hex — единственное, что о токене знает база. У токена 256 бит случайности, поэтому соль не нужна. */
    static String tokenHash(String token){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private AccountEntity byUsernameForUpdate(String username){return accounts.findByUsernameIgnoreCaseForUpdate(clean(username)).orElseThrow(()->new NoSuchElementException("User not found"));}
    private AccountEntity byIdForUpdate(String id){return accounts.findByIdForUpdate(id).orElseThrow(()->new NoSuchElementException("User not found"));}
    private static void validatePassword(String p){if(p==null||p.length()<6||p.length()>128)throw new IllegalArgumentException(PASSWORD_REQUIREMENTS);}
    private byte[] randomBytes(int n){byte[] b=new byte[n];random.nextBytes(b);return b;}
    private String hash(String password){validatePassword(password);byte[] salt=randomBytes(16);byte[] out=derive(password,salt,120_000,32);return "pbkdf2$120000$"+b64(salt)+"$"+b64(out);}
    private boolean verify(String p,String encoded){try{String[] x=encoded.split("\\$");return MessageDigest.isEqual(derive(p,Base64.getUrlDecoder().decode(x[2]),Integer.parseInt(x[1]),32),Base64.getUrlDecoder().decode(x[3]));}catch(Exception e){return false;}}
    private byte[] derive(String p,byte[] salt,int iter,int len){try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(p.toCharArray(),salt,iter,len*8)).getEncoded();}catch(Exception e){throw new IllegalStateException(e);}}
    private static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
}
