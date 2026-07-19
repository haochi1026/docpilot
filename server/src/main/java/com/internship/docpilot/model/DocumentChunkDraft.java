package com.internship.docpilot.model;

public class DocumentChunkDraft {
  private final Integer pageNo;
  private final String heading;
  private final String content;
  private final int tokenCount;

  public DocumentChunkDraft(Integer pageNo, String heading, String content, int tokenCount) {
    this.pageNo = pageNo;
    this.heading = heading;
    this.content = content;
    this.tokenCount = tokenCount;
  }

  public Integer getPageNo() {
    return pageNo;
  }

  public String getHeading() {
    return heading;
  }

  public String getContent() {
    return content;
  }

  public int getTokenCount() {
    return tokenCount;
  }
}
