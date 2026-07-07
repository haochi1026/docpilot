package com.internship.docpilot.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Map<String, Object>> business(BusinessException e) {
    return ResponseEntity.status(e.getStatus()).body(error(e.getStatus().value(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
    String m =
        e.getBindingResult().getFieldErrors().isEmpty()
            ? "参数不合法"
            : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
    return ResponseEntity.badRequest().body(error(400, m));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> other(Exception e) {
    return ResponseEntity.status(500).body(error(500, "服务暂时不可用"));
  }

  private Map<String, Object> error(int c, String m) {
    Map<String, Object> x = new LinkedHashMap<String, Object>();
    x.put("code", c);
    x.put("message", m);
    return x;
  }
}
