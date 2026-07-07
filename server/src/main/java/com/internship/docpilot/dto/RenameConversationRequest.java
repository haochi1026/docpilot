package com.internship.docpilot.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class RenameConversationRequest {
  @NotBlank @Size(max = 80) private String title;

  public String getTitle() { return title; }
  public void setTitle(String value) { title = value; }
}
