package com.internship.docpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RenameConversationRequest {
  @NotBlank @Size(max = 80) private String title;

  public String getTitle() { return title; }
  public void setTitle(String value) { title = value; }
}
