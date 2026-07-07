package com.internship.docpilot.model;

public class SearchHit {
  private Long chunkId;
  private Long documentId;
  private String documentName, content, embedding;
  private Integer pageNo;
  private double score;

  public SearchHit() {}

  public SearchHit(
      Long chunkId, Long id, String name, String content, Integer page, double score, String embedding) {
    this.chunkId = chunkId;
    this.documentId = id;
    this.documentName = name;
    this.content = content;
    this.pageNo = page;
    this.score = score;
    this.embedding = embedding;
  }

  public Long getChunkId() {
    return chunkId;
  }

  public void setChunkId(Long value) {
    chunkId = value;
  }

  public Long getDocumentId() {
    return documentId;
  }

  public void setDocumentId(Long v) {
    documentId = v;
  }

  public String getDocumentName() {
    return documentName;
  }

  public void setDocumentName(String v) {
    documentName = v;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String v) {
    content = v;
  }

  public Integer getPageNo() {
    return pageNo;
  }

  public void setPageNo(Integer v) {
    pageNo = v;
  }

  public double getScore() {
    return score;
  }

  public void setScore(double v) {
    score = v;
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public String getEmbedding() {
    return embedding;
  }

  public void setEmbedding(String value) {
    embedding = value;
  }
}
