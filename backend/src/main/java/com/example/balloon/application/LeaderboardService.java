package com.example.balloon.application;

import com.example.balloon.auth.*;
import com.example.balloon.domain.Models.Round;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class LeaderboardService {
  public record Entry(String id, String username, long balance, int rank, int level, long points, double multiplier, int rounds, int cashouts, long trend, Instant updatedAt) {}
  private final GameService game; private final AccountService accounts; private volatile Map<String,Integer> previous = Map.of();
  public LeaderboardService(GameService game, AccountService accounts){this.game=game;this.accounts=accounts;}
  public synchronized List<Entry> entries(String query,String sort,int offset,int limit){
    Map<String,Summary> grouped=new HashMap<>();
    for(Round r:game.allRounds()){ Summary s=grouped.computeIfAbsent(r.userId,k->new Summary()); s.points+=r.points; s.rounds++; s.multiplier=Math.max(s.multiplier,r.cashedOutMultiplier==null?r.multiplier:r.cashedOutMultiplier); if(r.payout>0)s.cashouts++; s.updated=r.endedAt==null?r.startedAt:r.endedAt; }
    for(AccountEntity a:accounts.all()){ Summary s=grouped.computeIfAbsent(a.getUsername(),k->new Summary()); s.balance=a.getBalance(); s.points=Math.max(s.points,a.getPoints()); s.username=a.getUsername(); s.id=a.getId(); }
    String q=query==null?"":query.toLowerCase(Locale.ROOT).trim(); Comparator<Map.Entry<String,Summary>> cmp=Comparator.comparingLong((Map.Entry<String,Summary>e)->e.getValue().points).reversed().thenComparing(e->e.getKey()); if("multiplier".equalsIgnoreCase(sort))cmp=Comparator.comparingDouble((Map.Entry<String,Summary>e)->e.getValue().multiplier).reversed().thenComparing(e->e.getKey()); else if("activity".equalsIgnoreCase(sort))cmp=Comparator.comparingInt((Map.Entry<String,Summary>e)->e.getValue().rounds).reversed().thenComparing(e->e.getKey());
    List<Map.Entry<String,Summary>> sorted=grouped.entrySet().stream().filter(e->q.isBlank()||e.getKey().toLowerCase(Locale.ROOT).contains(q)).sorted(cmp).toList(); Map<String,Integer> now=new HashMap<>(); for(int i=0;i<sorted.size();i++)now.put(sorted.get(i).getKey(),i+1); int from=Math.min(Math.max(0,offset),sorted.size()),to=Math.min(sorted.size(),from+Math.max(1,Math.min(limit,100))); List<Entry> out=new ArrayList<>(); for(int i=from;i<to;i++){var e=sorted.get(i);Summary s=e.getValue();int rank=i+1;Integer old=previous.get(e.getKey());out.add(new Entry(s.id==null?e.getKey():s.id,s.username==null?e.getKey():s.username,s.balance,rank,Math.max(1,1+(int)(s.points/250)),s.points,Math.round(s.multiplier*100.0)/100.0,s.rounds,s.cashouts,old==null?0:old-rank,s.updated));} previous=now; return out;
  }
  private static final class Summary {String id,username;long balance,points;double multiplier=1;int rounds,cashouts;Instant updated=Instant.now();}
}
