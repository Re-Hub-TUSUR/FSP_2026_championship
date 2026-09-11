package com.example.balloon.api;

import com.example.balloon.application.GameService;
import com.example.balloon.application.LeaderboardService;
import com.example.balloon.auth.AccountEntity;
import com.example.balloon.auth.AccountService;
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
  private final AccountService accounts;
  private final LeaderboardService leaderboard;
  public GameController(GameService game, AccountService accounts, LeaderboardService leaderboard) { this.game=game; this.accounts=accounts; this.leaderboard=leaderboard; }
  public record StartRequest(@NotNull Theme theme,@NotBlank String betOptionId,String idempotencyKey) {}
  @GetMapping("/me") public ResponseEntity<?> me(@RequestHeader(value="Authorization",required=false) String auth){try{return ResponseEntity.ok(AuthController.profile(accounts.require(auth)));}catch(Exception e){return ResponseEntity.status(401).body(Map.of("error",e.getMessage()));}}
  @GetMapping("/game/config") public Map<String,Object> config(@RequestParam(defaultValue="RED") Theme theme){return configMap(theme,game.config(theme));}
  @GetMapping("/rules") public Map<String,Object> rules(){return Map.of("title","Правила игры","sections",List.of(Map.of("heading","Темы и уровни","text","Красная тема содержит 12 уровней, зелёная — 9."),Map.of("heading","Cashout","text","Заберите выигрыш после первого уровня до crash."),Map.of("heading","Бустеры","text","Бустер активируется на серверном уровне и начисляет дополнительные очки.")));}
  @PostMapping("/rounds") public ResponseEntity<?> start(@Valid @RequestBody StartRequest req,@RequestHeader(value="Authorization",required=false) String auth,@RequestHeader(value="Idempotency-Key",required=false) String key){try{AccountEntity user=accounts.require(auth);return ResponseEntity.status(201).body(toMap(game.startIdempotent(user.getUsername(),req.theme(),req.betOptionId(),key!=null?key:req.idempotencyKey())));}catch(SecurityException e){return ResponseEntity.status(401).body(Map.of("error",e.getMessage()));}catch(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
  @GetMapping("/rounds/history") public List<Map<String,Object>> history(@RequestParam(defaultValue="50") int limit){return game.history().stream().limit(Math.max(1,Math.min(100,limit))).map(this::toMap).toList();}
  @GetMapping("/rounds/{id}") public Map<String,Object> get(@PathVariable String id){return toMap(game.get(id));}
  @PostMapping("/rounds/{id}/cashout") public ResponseEntity<?> cashout(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth){try{accounts.require(auth);return ResponseEntity.ok(toMap(game.cashout(id)));}catch(SecurityException e){return ResponseEntity.status(401).body(Map.of("error",e.getMessage()));}catch(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
  @GetMapping("/rounds/{id}/proof") public Map<String,Object> proof(@PathVariable String id){return game.proof(id);}
  @GetMapping("/leaderboard") public Map<String,Object> leaderboard(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="score") String sort,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return Map.of("items",leaderboard.entries(q,sort,offset,limit),"updatedAt",Instant.now());}
  @GetMapping("/admin/config") public ResponseEntity<?> adminConfig(@RequestParam(defaultValue="RED") Theme theme,@RequestHeader(value="Authorization",required=false) String auth){try{accounts.requireAdmin(auth);return ResponseEntity.ok(configMap(theme,game.config(theme)));}catch(Exception e){return ResponseEntity.status(403).body(Map.of("error",e.getMessage()));}}
  @PutMapping("/admin/config") public ResponseEntity<?> update(@RequestParam(defaultValue="RED") Theme theme,@RequestBody Map<String,Object> body,@RequestHeader(value="Authorization",required=false) String auth){try{AccountEntity admin=accounts.requireAdmin(auth);return ResponseEntity.ok(configMap(theme,game.updateConfig(theme,parseConfig(theme,body))));}catch(Exception e){return ResponseEntity.status(403).body(Map.of("error",e.getMessage()));}}
  private Config parseConfig(Theme theme,Map<String,Object> body){Config old=game.config(theme);long[] bets=old.bets();double[] boosters=old.boosters();if(body.get("betOptions") instanceof List<?> options&&options.size()==4){bets=new long[4];boosters=new double[4];for(int i=0;i<4;i++){Map<?,?>o=(Map<?,?>)options.get(i);bets[i]=o.get("amount") instanceof Number n?n.longValue():old.bets()[i];boosters[i]=o.get("boosterMultiplier") instanceof Number n?n.doubleValue():old.boosters()[i];}}return new Config(theme,number(body,"levels",old.levels()).intValue(),bets,boosters,number(body,"pointsPerLevel",old.pointsPerLevel()).intValue(),number(body,"pointsCashoutBonus",old.pointsCashoutBonus()).intValue(),number(body,"pointsBoosterBonus",old.pointsBoosterBonus()).intValue(),number(body,"baseHazard",old.baseHazard()).doubleValue(),number(body,"hazardGrowth",old.hazardGrowth()).doubleValue(),number(body,"maxHazard",old.maxHazard()).doubleValue(),number(body,"rtp",old.rtp()).doubleValue(),old.version());}
  private static Number number(Map<String,Object>b,String k,Number f){return b.get(k) instanceof Number n?n:f;}
  private Map<String,Object> configMap(Theme t,Config c){List<Map<String,Object>>o=new ArrayList<>();for(int i=0;i<c.bets().length;i++)o.add(Map.of("id",t.name().toLowerCase()+"-x"+(i+1),"amount",c.bets()[i],"boosterMultiplier",c.boosters()[i]));double[] ladder=c.ladder();List<Map<String,Object>> levelTable=new ArrayList<>();for(int level=1;level<=c.levels();level++)levelTable.add(Map.of("level",level,"multiplier",ladder[level],"popChance",c.hazardAt(level)));Map<String,Object> m=new LinkedHashMap<>();m.put("gameId","balloon");m.put("gameName","Воздушный шар");m.put("theme",t);m.put("levels",c.levels());m.put("betOptions",o);m.put("pointsPerLevel",c.pointsPerLevel());m.put("pointsCashoutBonus",c.pointsCashoutBonus());m.put("pointsBoosterBonus",c.pointsBoosterBonus());m.put("ladder",Arrays.stream(ladder).boxed().toList());m.put("levelTable",levelTable);m.put("maxMultiplier",ladder[c.levels()]);m.put("rtp",c.rtp());m.put("baseHazard",c.baseHazard());m.put("hazardGrowth",c.hazardGrowth());m.put("maxHazard",c.maxHazard());m.put("version",c.version());return m;}
  private Map<String,Object> toMap(Round r){Map<String,Object>m=new LinkedHashMap<>();m.put("roundId",r.id);m.put("state",r.state);m.put("theme",r.theme());m.put("betAmount",r.betAmount);m.put("boosterMultiplier",r.boosterMultiplier);m.put("boosterLevel",r.boosterLevel);m.put("boosterActivated",r.boosterActivated);m.put("levels",r.levels());m.put("level",r.level);m.put("multiplier",r.multiplier);m.put("nextMultiplier",r.nextMultiplier());m.put("potentialPayout",Math.round(r.betAmount*r.multiplier));m.put("payout",r.payout);m.put("profit",r.profit());m.put("points",r.points);m.put("balanceAfter",r.balanceAfter);m.put("commitment","sha256:"+r.commitment);m.put("serverTime",Instant.now());m.put("createdAt",r.startedAt);if(r.cashedOutMultiplier!=null)m.put("cashedOutMultiplier",r.cashedOutMultiplier);if(r.endedAt!=null)m.put("endedAt",r.endedAt);return m;}
}
