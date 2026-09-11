package com.example.balloon.domain;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class Models {
  public enum Theme { RED, GREEN }
  public enum RoundState { CREATED, STARTED, CASHED_OUT, CRASHED, SETTLED }
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Config(Theme theme,int levels,long[] bets,double[] boosters,int pointsPerLevel,int pointsCashoutBonus,int pointsBoosterBonus,long version) {
    public static Config defaults(Theme t){ return new Config(t,t==Theme.RED?12:9,new long[]{10,20,50,100},new double[]{1,2,3,4},10,20,15,1); }
  }
  public static class Round {
    public final String id,userId; public final Theme theme; public final long betAmount; public final double boosterMultiplier; public final int levels; public final long configVersion; public final String seed,commitment; public final double crashMultiplier; public final int boosterLevel;
    public final int pointsPerLevel,pointsBoosterBonus,pointsCashoutBonus;
    public volatile int level=0; public volatile double multiplier=1.0; public volatile RoundState state=RoundState.STARTED; public volatile Double cashedOutMultiplier; public volatile long payout=0; public volatile int points=0; public volatile boolean boosterActivated=false; public final Instant startedAt=Instant.now(); public volatile Instant endedAt;
    public Round(String id,String u,Theme t,long b,double bm,int l,long cv,String s,String c,double crash,int bl,int ppl,int pbb,int pcb){this.id=id;userId=u;theme=t;betAmount=b;boosterMultiplier=bm;levels=l;configVersion=cv;seed=s;commitment=c;crashMultiplier=crash;boosterLevel=bl;pointsPerLevel=ppl;pointsBoosterBonus=pbb;pointsCashoutBonus=pcb;}
  }
}
