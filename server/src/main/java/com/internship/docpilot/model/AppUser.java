package com.internship.docpilot.model;

public class AppUser {
  private Long id;
  private String username, passwordHash, displayName, role;
  private boolean enabled;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String v) {
    username = v;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String v) {
    passwordHash = v;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String v) {
    displayName = v;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String v) {
    role = v;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }
}
