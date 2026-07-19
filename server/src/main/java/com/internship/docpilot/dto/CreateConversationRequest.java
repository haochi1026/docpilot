package com.internship.docpilot.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateConversationRequest {
  @NotNull private Long kbId;
  @Size(max = 80) private String title;

  public Long getKbId() { return kbId; }
  public void setKbId(Long value) { kbId = value; }
  public String getTitle() { return title; }
  public void setTitle(String value) { title = value; }
}
