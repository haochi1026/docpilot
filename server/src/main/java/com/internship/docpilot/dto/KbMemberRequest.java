package com.internship.docpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class KbMemberRequest {
  @NotBlank private String username;

  @Pattern(regexp = "READ|MANAGE", message = "权限只能是 READ 或 MANAGE")
  private String permission = "READ";

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPermission() {
    return permission;
  }

  public void setPermission(String permission) {
    this.permission = permission;
  }
}
