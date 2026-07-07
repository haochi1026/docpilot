package com.internship.docpilot.config;

public class UserPrincipal {
  private final Long userId;
  private final String username;
  private final String role;

  public UserPrincipal(Long userId, String username, String role) {
    this.userId = userId;
    this.username = username;
    this.role = role;
  }

  public Long getUserId() {
    return userId;
  }

  public String getUsername() {
    return username;
  }

  public String getRole() {
    return role;
  }
}
