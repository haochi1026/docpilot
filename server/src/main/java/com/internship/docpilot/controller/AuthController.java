package com.internship.docpilot.controller;

import com.internship.docpilot.dto.LoginRequest;
import com.internship.docpilot.dto.LoginResponse;
import com.internship.docpilot.service.AuthService;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService service;

  public AuthController(AuthService s) {
    service = s;
  }

  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest r) {
    return service.login(r);
  }
}
