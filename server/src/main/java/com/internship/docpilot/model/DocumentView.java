package com.internship.docpilot.model;

import java.time.LocalDateTime;

public class DocumentView {
  private Long id, kbId;
  private String originalName, contentType, status, errorMessage;
  private long sizeBytes;
  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getKbId() {
    return kbId;
  }

  public void setKbId(Long v) {
    kbId = v;
  }

  public String getOriginalName() {
    return originalName;
  }

  public void setOriginalName(String v) {
    originalName = v;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String v) {
    contentType = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String v) {
    errorMessage = v;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(long v) {
    sizeBytes = v;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime v) {
    createdAt = v;
  }
}
