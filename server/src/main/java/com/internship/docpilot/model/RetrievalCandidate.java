package com.internship.docpilot.model;

public class RetrievalCandidate {
  private final Long chunkId;
  private final double score;

  public RetrievalCandidate(Long chunkId, double score) {
    this.chunkId = chunkId;
    this.score = score;
  }

  public Long getChunkId() {
    return chunkId;
  }

  public double getScore() {
    return score;
  }
}
