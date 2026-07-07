package com.internship.docpilot.dto;

public class LoginResponse {
  private final String token, displayName, role;
  private final Long userId;

  public LoginResponse(String t, Long id, String n, String r) {
    token = t;
    userId = id;
    displayName = n;
    role = r;
  }

  public String getToken() {
    return token;
  }

  public Long getUserId() {
    return userId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getRole() {
    return role;
  }
}
