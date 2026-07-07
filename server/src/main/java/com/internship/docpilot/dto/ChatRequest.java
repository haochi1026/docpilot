package com.internship.docpilot.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

public class ChatRequest {
  @NotNull private Long kbId;
  private Long conversationId;
  @NotBlank private String question;

  public Long getKbId() {
    return kbId;
  }

  public void setKbId(Long v) {
    kbId = v;
  }

  public Long getConversationId() {
    return conversationId;
  }

  public void setConversationId(Long value) {
    conversationId = value;
  }

  public String getQuestion() {
    return question;
  }

  public void setQuestion(String v) {
    question = v;
  }
}
