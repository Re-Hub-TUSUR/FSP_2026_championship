package com.example.balloon.api;

import com.example.balloon.application.GameService;
import com.example.balloon.domain.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", maxAge = 3600)
public class GameController {
  private final GameService game;
  public GameController(GameService game){this.game=game;}
  public record StartRequest(@NotNull Theme theme,@NotBlank String betOptionId,String idempotencyKey){}

  @GetMapping("/me") public Map<String,Object> me(){return Map.of("userId","demo","username","demo","role","PLAYER","balance",game.balance());}
  @GetMapping("/game/config") public Map<String,Object> config(@RequestParam(defaultValue="RED") Theme theme){return configMap(theme,game.config(theme));}
  @GetMapping("/rules") public Map<String,Object> rules(){return Map.of("title","Правила игры","sections",List.of(Map.of("heading","Темы и уровни","text","Красная тема содержит 12 уровней, зелёная — 9."),Map.of("heading","Cashout","text","Заберите выигрыш после первого уровня до момента crash."),Map.of("heading","Бустеры","text","При достижении уровня бустер умножает коэффициент и начисляет очки.")));}
  @PostMapping("/rounds") public ResponseEntity<?> start(@Valid @RequestBody StartRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key){try{return ResponseEntity.status(HttpStatus.CREATED).body(toMap(game.startIdempotent("demo",req.theme(),req.betOptionId(),key!=null?key:req.idempotencyKey())));}catch(IllegalStateException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
  @GetMapping("/rounds/{id}") public Map<String,Object> get(@PathVariable String id){return toMap(game.get(id));}
  @PostMapping("/rounds/{id}/cashout") public ResponseEntity<?> cashout(@PathVariable String id){try{return ResponseEntity.ok(toMap(game.cashout(id)));}catch(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
  @GetMapping("/rounds/history") public List<Map<String,Object>> history(@RequestParam(defaultValue="50") int limit){int n=Math.max(1,Math.min(100,limit));return game.history().stream().limit(n).map(this::toMap).toList();}
  @GetMapping("/rounds/{id}/proof") public Map<String,Object> proof(@PathVariable String id){return game.proof(id);}
  @GetMapping("/admin/config") public Map<String,Object> adminConfig(@RequestParam(defaultValue="RED") Theme theme){return configMap(theme,game.config(theme));}
  @PutMapping("/admin/config") public Map<String,Object> update(@RequestParam(defaultValue="RED") Theme theme,@RequestBody Map<String,Object> body){return configMap(theme,game.updateConfig(theme,parseConfig(theme,body)));}

  private Config parseConfig(Theme theme,Map<String,Object> body){Config old=game.config(theme); List<?> options=(List<?>)body.get("betOptions"); long[] bets=old.bets(); double[] boosters=old.boosters(); if(options!=null&&options.size()==4){bets=new long[4];boosters=new double[4];for(int i=0;i<4;i++){Map<?,?> o=(Map<?,?>)options.get(i);Object amount=o.get("amount"), booster=o.get("boosterMultiplier"); bets[i]=amount instanceof Number n?n.longValue():bets[i]; boosters[i]=booster instanceof Number n?n.doubleValue():boosters[i];}} int levels=((Number)body.getOrDefault("levels",old.levels())).intValue(); int ppl=((Number)body.getOrDefault("pointsPerLevel",old.pointsPerLevel())).intValue(); return new Config(theme,levels,bets,boosters,ppl,old.pointsCashoutBonus(),old.pointsBoosterBonus(),old.version());}
  private Map<String,Object> configMap(Theme theme,Config c){List<Map<String,Object>> opts=new ArrayList<>();for(int i=0;i<c.bets().length;i++)opts.add(Map.of("id",theme.name().toLowerCase()+"-x"+(i+1),"amount",c.bets()[i],"boosterMultiplier",c.boosters()[Math.min(i,c.boosters().length-1)]));return Map.of("gameId","balloon","gameName","Воздушный шар","theme",theme,"levels",c.levels(),"betOptions",opts,"pointsPerLevel",c.pointsPerLevel(),"version",c.version());}
  private Map<String,Object> toMap(Round r){Map<String,Object> m=new LinkedHashMap<>();m.put("roundId",r.id);m.put("state",r.state);m.put("theme",r.theme);m.put("betAmount",r.betAmount);m.put("boosterMultiplier",r.boosterMultiplier);m.put("levels",r.levels);m.put("level",r.level);m.put("multiplier",r.multiplier);m.put("payout",r.payout);m.put("points",r.points);m.put("boosterActivated",r.boosterActivated);m.put("commitment","sha256:"+r.commitment);m.put("serverTime",Instant.now());m.put("createdAt",r.startedAt);if(r.cashedOutMultiplier!=null)m.put("cashedOutMultiplier",r.cashedOutMultiplier);if(r.endedAt!=null)m.put("endedAt",r.endedAt);return m;}
}

