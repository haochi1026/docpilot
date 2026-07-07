package com.internship.docpilot.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
  private final HttpStatus status;

  public BusinessException(HttpStatus s, String m) {
    super(m);
    status = s;
  }

  public HttpStatus getStatus() {
    return status;
  }
}
