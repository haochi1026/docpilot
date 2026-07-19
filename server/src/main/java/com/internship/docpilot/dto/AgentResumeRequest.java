package com.internship.docpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class AgentResumeRequest {
  @NotNull private Long kbId;
  @NotNull private Long conversationId;
  @NotBlank private String approvalId;

  @NotBlank
  @Pattern(regexp = "approve|reject")
  private String decision;

  public Long getKbId() {
    return kbId;
  }

  public void setKbId(Long kbId) {
    this.kbId = kbId;
  }

  public Long getConversationId() {
    return conversationId;
  }

  public void setConversationId(Long conversationId) {
    this.conversationId = conversationId;
  }

  public String getDecision() {
    return decision;
  }

  public String getApprovalId() {
    return approvalId;
  }

  public void setApprovalId(String approvalId) {
    this.approvalId = approvalId;
  }

  public void setDecision(String decision) {
    this.decision = decision;
  }
}
