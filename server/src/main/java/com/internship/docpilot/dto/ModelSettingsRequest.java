package com.internship.docpilot.dto;

import javax.validation.constraints.NotBlank;

public class ModelSettingsRequest {
  @NotBlank private String aiMode;
  @NotBlank private String aiBaseUrl;
  @NotBlank private String aiModel;
  @NotBlank private String embeddingMode;
  @NotBlank private String embeddingBaseUrl;
  @NotBlank private String embeddingModel;

  public String getAiMode() { return aiMode; }
  public void setAiMode(String value) { aiMode = value; }
  public String getAiBaseUrl() { return aiBaseUrl; }
  public void setAiBaseUrl(String value) { aiBaseUrl = value; }
  public String getAiModel() { return aiModel; }
  public void setAiModel(String value) { aiModel = value; }
  public String getEmbeddingMode() { return embeddingMode; }
  public void setEmbeddingMode(String value) { embeddingMode = value; }
  public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
  public void setEmbeddingBaseUrl(String value) { embeddingBaseUrl = value; }
  public String getEmbeddingModel() { return embeddingModel; }
  public void setEmbeddingModel(String value) { embeddingModel = value; }
}
