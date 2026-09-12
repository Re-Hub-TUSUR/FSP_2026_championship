package com.example.balloon.auth;

import com.example.balloon.domain.Models.PuzzleAward;
import com.example.balloon.daily.DailyBonusService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@Service
public class AccountService implements CommandLineRunner {
    /** Количество фрагментов дневного пазла. Награда определяется билетом, а не фиксированной суммой. */
    public static final int PUZZLE_FRAGMENTS = DailyBonusService.PUZZLE_FRAGMENTS;
    /**
     * @deprecated оставлено только для бинарной/исходной совместимости старых клиентов;
     * сбор пазла больше не начисляет эту сумму — награда приходит через claim билета.
     */
    @Deprecated public static final long PUZZLE_BONUS = 250;

    private final AccountRepository accounts; private final AuditRepository audits; private final DailyBonusService dailyBonus;
    private final Map<String,String> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    public AccountService(AccountRepository accounts, AuditRepository audits, DailyBonusService dailyBonus){this.accounts=accounts;this.audits=audits;this.dailyBonus=dailyBonus;}
    @Override @Transactional public void run(String... args){
        if(!accounts.existsByUsernameIgnoreCase("demo")) accounts.save(new AccountEntity("demo", hash("demo123"), "USER"));
        if(!accounts.existsByUsernameIgnoreCase("admin")) accounts.save(new AccountEntity("admin", hash("admin123"), "ADMIN"));
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
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32)); sessions.put(token,a.getId()); return token;
    }
    public void logout(String token){if(token!=null)sessions.remove(token);}
    public AccountEntity current(String auth){
        if(auth==null||auth.isBlank()) return null; String token=auth.startsWith("Bearer ")?auth.substring(7):auth;
        String id=sessions.get(token); return id==null?null:accounts.findById(id).orElse(null);
    }
    public AccountEntity require(String auth){AccountEntity a=current(auth);if(a==null)throw new SecurityException("Authentication required");return a;}
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
    public AccountEntity byId(String id){return accounts.findById(id).orElseThrow(()->new NoSuchElementException("User not found"));}
    @Transactional public AccountEntity setBlocked(String id,boolean blocked,String actor,String reason){AccountEntity a=byIdForUpdate(id);a.setBlocked(blocked);accounts.save(a);audit(actor,blocked?"USER_BLOCK":"USER_UNBLOCK",a.getUsername()+": "+reason);return a;}
    @Transactional public AccountEntity setRole(String id,String role,String actor,String reason){if(!Set.of("USER","ADMIN").contains(role))throw new IllegalArgumentException("Role must be USER or ADMIN");AccountEntity a=byIdForUpdate(id);a.setRole(role);accounts.save(a);audit(actor,"ROLE_CHANGE",a.getUsername()+" -> "+role+": "+reason);return a;}
    @Transactional public AccountEntity adjust(String id,long balanceDelta,long pointsDelta,String actor,String reason){AccountEntity a=byIdForUpdate(id);a.setBalance(Math.max(0,a.getBalance()+balanceDelta));a.setPoints(Math.max(0,a.getPoints()+pointsDelta));accounts.save(a);audit(actor,"MANUAL_ADJUSTMENT",a.getUsername()+": "+reason+" [balance="+balanceDelta+", points="+pointsDelta+"]");return a;}
    public List<AuditEntity> audit(){return audits.findTop100ByOrderByCreatedAtDesc();}
    public void audit(String actor,String action,String details){audits.save(new AuditEntity(actor,action,details));}
    private static String clean(String s){if(s==null||!s.matches("[A-Za-z0-9_\\-]{3,40}"))throw new IllegalArgumentException("Username must be 3-40 latin characters");return s.toLowerCase(Locale.ROOT);}
    private AccountEntity byUsernameForUpdate(String username){return accounts.findByUsernameIgnoreCaseForUpdate(clean(username)).orElseThrow(()->new NoSuchElementException("User not found"));}
    private AccountEntity byIdForUpdate(String id){return accounts.findByIdForUpdate(id).orElseThrow(()->new NoSuchElementException("User not found"));}
    private static void validatePassword(String p){if(p==null||p.length()<6||p.length()>128)throw new IllegalArgumentException("Password must be 6-128 characters");}
    private byte[] randomBytes(int n){byte[] b=new byte[n];random.nextBytes(b);return b;}
    private String hash(String password){validatePassword(password);byte[] salt=randomBytes(16);byte[] out=derive(password,salt,120_000,32);return "pbkdf2$120000$"+b64(salt)+"$"+b64(out);}
    private boolean verify(String p,String encoded){try{String[] x=encoded.split("\\$");return MessageDigest.isEqual(derive(p,Base64.getUrlDecoder().decode(x[2]),Integer.parseInt(x[1]),32),Base64.getUrlDecoder().decode(x[3]));}catch(Exception e){return false;}}
    private byte[] derive(String p,byte[] salt,int iter,int len){try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(p.toCharArray(),salt,iter,len*8)).getEncoded();}catch(Exception e){throw new IllegalStateException(e);}}
    private static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
}
