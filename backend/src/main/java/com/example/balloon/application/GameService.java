package com.example.balloon.application;

import com.example.balloon.domain.Models.*;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class GameService {
    private final Map<String,Round> rounds=new ConcurrentHashMap<>();
    private final Map<String,Round> idempotent=new ConcurrentHashMap<>();
    private final Map<Theme,Config> configs=new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final SecureRandom random=new SecureRandom();
    private long balance=1000;

    public GameService() {
        configs.put(Theme.RED,Config.defaults(Theme.RED));
        configs.put(Theme.GREEN,Config.defaults(Theme.GREEN));
        scheduler=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"round-ticker");t.setDaemon(true);return t;});
    }
    public Config config(Theme t){return configs.get(t);}
    public synchronized long balance(){return balance;}

    public synchronized Round start(String user,Theme theme,String betOption){
        Config c=Optional.ofNullable(config(theme)).orElseThrow();
        int index=optionIndex(betOption,c.bets().length); long bet=c.bets()[index];
        if(balance<bet) throw new IllegalStateException("Insufficient balance"); balance-=bet;
        String id=UUID.randomUUID().toString(), seed=randomSeed();
        String commitment=sha256(seed+id+c.version()); double crash=crashMultiplier(seed,id);
        int boosterLevel=1+random.nextInt(c.levels()); double booster=c.boosters()[Math.min(index,c.boosters().length-1)];
        Round round=new Round(id,user,theme,bet,booster,c.levels(),c.version(),seed,commitment,crash,boosterLevel,c.pointsPerLevel(),c.pointsBoosterBonus(),c.pointsCashoutBonus());
        rounds.put(id,round); scheduler.scheduleAtFixedRate(()->tick(round),1,1,TimeUnit.SECONDS); return round;
    }
    public synchronized Round startIdempotent(String user,Theme t,String betOption,String key){
        if(key!=null&&!key.isBlank()){Round old=idempotent.get(user+":"+key);if(old!=null)return old;}
        Round r=start(user,t,betOption);if(key!=null&&!key.isBlank())idempotent.put(user+":"+key,r);return r;
    }
    private void tick(Round r){synchronized(r){
        if(r.state!=RoundState.STARTED&&r.state!=RoundState.CASHED_OUT)return;
        if(r.level>=r.levels){r.state=RoundState.SETTLED;r.endedAt=Instant.now();return;}
        boolean hadCashout = r.state == RoundState.CASHED_OUT; int next=r.level+1;double nextMult=r.multiplier+0.22+next*0.015;
        boolean booster=next==r.boosterLevel&&!r.boosterActivated;if(booster)nextMult*=r.boosterMultiplier;
        if(nextMult>=r.crashMultiplier){r.state=hadCashout?RoundState.SETTLED:RoundState.CRASHED;r.endedAt=Instant.now();return;}
        r.level=next;r.multiplier=Math.round(nextMult*100.0)/100.0;r.points+=r.pointsPerLevel;
        if(booster){r.boosterActivated=true;r.points+=r.pointsBoosterBonus;}
        if(r.level>=r.levels){r.state=RoundState.SETTLED;r.endedAt=Instant.now();}
    }}
    public Round get(String id){Round r=rounds.get(id);if(r==null)throw new NoSuchElementException("Round not found: "+id);return r;}
    public synchronized Round cashout(String id){Round r=get(id);synchronized(r){
        if(r.state==RoundState.CASHED_OUT)return r;
        if(r.state!=RoundState.STARTED)throw new IllegalStateException("Round already finished");
        if(r.level<1)throw new IllegalStateException("Cashout allowed after first level");
        r.cashedOutMultiplier=r.multiplier;r.payout=Math.max(1,Math.round(r.betAmount*r.multiplier));r.points+=r.pointsCashoutBonus;balance+=r.payout;r.state=RoundState.CASHED_OUT;return r;
    }}
    public List<Round> history(){return rounds.values().stream().filter(r->r.state==RoundState.CRASHED||r.state==RoundState.CASHED_OUT||r.state==RoundState.SETTLED).sorted(Comparator.comparing((Round r)->r.startedAt).reversed()).toList();}
    public synchronized Config updateConfig(Theme t,Config req){Config cur=config(t);if(req==null)throw new IllegalArgumentException("Configuration is required");int levels=req.levels()>0?req.levels():cur.levels();long[] bets=req.bets()==null||req.bets().length==0?cur.bets():req.bets();double[] boosters=req.boosters()==null||req.boosters().length==0?cur.boosters():req.boosters();if(levels<1||levels>30||bets.length!=4||boosters.length!=4)throw new IllegalArgumentException("Invalid levels, bets or boosters");Config n=new Config(t,levels,bets,boosters,Math.max(0,req.pointsPerLevel()),Math.max(0,req.pointsCashoutBonus()),Math.max(0,req.pointsBoosterBonus()),cur.version()+1);configs.put(t,n);return n;}
    public Map<String,Object> proof(String id){Round r=get(id);Map<String,Object> m=new LinkedHashMap<>();m.put("roundId",r.id);m.put("commitment","sha256:"+r.commitment);if(r.state!=RoundState.STARTED&&r.state!=RoundState.CASHED_OUT){m.put("seed",r.seed);m.put("crashMultiplier",r.crashMultiplier);m.put("boosterLevel",r.boosterLevel);}return m;}
    private int optionIndex(String o,int count){if(o==null)return Math.min(1,count-1);try{int n=Integer.parseInt(o.replaceAll("\\D",""));return Math.max(0,Math.min(count-1,n-1));}catch(NumberFormatException e){return Math.min(1,count-1);}}
    private double crashMultiplier(String seed,String id){double u=hmacUnit(seed,id);double v=1.55+(-Math.log(Math.max(1e-9,1-u))*1.55);return Math.min(7.5,Math.max(1.8,Math.round(v*100.0)/100.0));}
    private static double hmacUnit(String seed,String id){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(seed.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));byte[] d=mac.doFinal(id.getBytes(StandardCharsets.UTF_8));long v=0;for(int i=0;i<8;i++)v=(v<<8)|(d[i]&0xffL);return (v>>>1)/(double)Long.MAX_VALUE;}catch(Exception e){throw new IllegalStateException(e);}}
    private String randomSeed(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private static String sha256(String s){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder(64);for(byte x:d)b.append(String.format("%02x",x));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}

