package com.internship.docpilot.service;

import com.internship.docpilot.config.JwtService;
import com.internship.docpilot.dto.LoginRequest;
import com.internship.docpilot.dto.LoginResponse;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.AppUser;
import com.internship.docpilot.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final JwtService jwt;

  public AuthService(UserRepository u, PasswordEncoder e, JwtService j) {
    users = u;
    encoder = e;
    jwt = j;
  }

  public LoginResponse login(LoginRequest r) {
    AppUser u = users.find(r.getUsername());
    if (u == null || !u.isEnabled() || !encoder.matches(r.getPassword(), u.getPasswordHash()))
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    return new LoginResponse(
        jwt.create(u.getId(), u.getUsername(), u.getRole()),
        u.getId(),
        u.getDisplayName(),
        u.getRole());
  }
}
