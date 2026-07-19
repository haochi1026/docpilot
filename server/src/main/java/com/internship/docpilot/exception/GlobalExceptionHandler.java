package com.internship.docpilot.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
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
    String requestId = UUID.randomUUID().toString();
    log.error("Unhandled request failure requestId={}", requestId, e);
    Map<String, Object> body = error(500, "服务暂时不可用");
    body.put("requestId", requestId);
    return ResponseEntity.status(500).body(body);
  }

  private Map<String, Object> error(int c, String m) {
    Map<String, Object> x = new LinkedHashMap<String, Object>();
    x.put("code", c);
    x.put("message", m);
    return x;
  }
}
