package com.example.balloon.api;
import com.example.balloon.auth.*; import jakarta.validation.Valid; import jakarta.validation.constraints.*; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/v1/auth") @CrossOrigin(origins="*",maxAge=3600)
public class AuthController {
 private final AccountService service; public AuthController(AccountService service){this.service=service;}
 public record RegisterRequest(@NotBlank @Size(min=3,max=40) String username,@NotBlank @Size(min=6,max=128) String password){}
 public record LoginRequest(@NotBlank String username,@NotBlank String password){}
 @PostMapping("/register") public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req){try{return ResponseEntity.status(201).body(profile(service.register(req.username(),req.password())));}catch(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
 @PostMapping("/login") public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req){try{String token=service.login(req.username(),req.password());return ResponseEntity.ok(Map.of("token",token,"user",profile(service.current(token))));}catch(Exception e){return ResponseEntity.status(401).body(Map.of("error",e.getMessage()));}}
 @PostMapping("/logout") public Map<String,Object> logout(@RequestHeader(value="Authorization",required=false) String auth){service.logout(auth==null?null:auth.replaceFirst("^Bearer ",""));return Map.of("ok",true);}
 @GetMapping("/me") public ResponseEntity<?> me(@RequestHeader(value="Authorization",required=false) String auth){try{return ResponseEntity.ok(profile(service.require(auth)));}catch(Exception e){return ResponseEntity.status(401).body(Map.of("error",e.getMessage()));}}
 static Map<String,Object> profile(AccountEntity a){
  Map<String,Object> profile=new LinkedHashMap<>();
  profile.put("id",a.getId()); profile.put("username",a.getUsername()); profile.put("role",a.getRole());
  profile.put("blocked",a.isBlocked()); profile.put("balance",a.getBalance()); profile.put("points",a.getPoints());
  profile.put("level",a.getLevel()); profile.put("createdAt",a.getCreatedAt());
  profile.put("puzzleMask",a.getPuzzleMask()); profile.put("puzzleFragments",AccountService.PUZZLE_FRAGMENTS);
  profile.put("puzzleBonus",AccountService.PUZZLE_BONUS); profile.put("puzzlesCompleted",a.getPuzzlesCompleted());
  return profile;
 }
}
