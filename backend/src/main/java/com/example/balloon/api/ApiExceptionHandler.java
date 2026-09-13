package com.example.balloon.api;

import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Map<String, Object>> notFound(Exception e) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, Object>> badRequest(Exception e) {
    return problem(HttpStatus.BAD_REQUEST, "Bad Request", e.getMessage());
  }

  /**
   * Bean Validation failures happen before a controller method is entered.  Do
   * not expose Spring's full exception message (which includes rejected input
   * and Java implementation details); return the constraint messages intended
   * for the user instead.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
    String detail = e.getBindingResult().getFieldErrors().stream()
        .map(ApiExceptionHandler::validationMessage)
        .filter(message -> !message.isBlank())
        .distinct()
        .collect(Collectors.joining("; "));
    return problem(HttpStatus.BAD_REQUEST, "Bad Request",
        detail.isBlank() ? "Проверьте введённые данные" : detail);
  }

  private static String validationMessage(FieldError error) {
    String message = error.getDefaultMessage();
    return message == null || message.isBlank() ? "Некорректное значение поля" : message;
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> generic(Exception e) {
    // Собственные исключения Spring MVC (415, 405, невалидное тело) должны сохранять свой статус, а не превращаться в 500.
    if (e instanceof ErrorResponse response) {
      HttpStatusCode status = response.getStatusCode();
      return problem(status, HttpStatus.resolve(status.value()) instanceof HttpStatus resolved ? resolved.getReasonPhrase() : "Error", e.getMessage());
    }
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", e.getMessage() == null ? "error" : e.getMessage());
  }

  private static ResponseEntity<Map<String, Object>> problem(HttpStatusCode status, String title, String detail) {
    return ResponseEntity.status(status).body(Map.of(
        "type", "about:blank",
        "title", title,
        "status", status.value(),
        "detail", detail == null ? title : detail));
  }
}
