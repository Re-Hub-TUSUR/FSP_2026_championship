package com.example.balloon.api;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestControllerAdvice
public class ApiExceptionHandler {
 @ExceptionHandler(NoSuchElementException.class) ResponseEntity<Map<String,Object>> notFound(Exception e){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("type","about:blank","title","Not Found","status",404,"detail",e.getMessage()));}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,Object>> badRequest(Exception e){return ResponseEntity.badRequest().body(Map.of("type","about:blank","title","Bad Request","status",400,"detail",e.getMessage()));}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> generic(Exception e){return ResponseEntity.status(500).body(Map.of("type","about:blank","title","Internal Server Error","status",500,"detail",e.getMessage()==null?"error":e.getMessage()));}
}
