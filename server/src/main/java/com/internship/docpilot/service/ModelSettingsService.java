package com.internship.docpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.dto.ModelSettingsRequest;
import com.internship.docpilot.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ModelSettingsService {
  private final JdbcTemplate jdbc;
  private final RestTemplate http = new RestTemplate();
  private final ObjectMapper mapper = new ObjectMapper();
  private final Snapshot defaults;
  private final String aiApiKey;
  private final String embeddingApiKey;

  public ModelSettingsService(
      JdbcTemplate jdbc,
      @Value("${app.ai.mode:local}") String aiMode,
      @Value("${app.ai.base-url:http://host.docker.internal:11434/v1}") String aiBaseUrl,
      @Value("${app.ai.api-key:ollama}") String aiApiKey,
      @Value("${app.ai.model:qwen3.5:2b}") String aiModel,
      @Value("${app.embedding.mode:local}") String embeddingMode,
      @Value("${app.embedding.base-url:http://host.docker.internal:11434/v1}") String embeddingBaseUrl,
      @Value("${app.embedding.api-key:ollama}") String embeddingApiKey,
      @Value("${app.embedding.model:qwen3-embedding:0.6b}") String embeddingModel) {
    this.jdbc = jdbc;
    this.aiApiKey = aiApiKey;
    this.embeddingApiKey = embeddingApiKey;
    defaults = new Snapshot(aiMode, aiBaseUrl, aiModel, embeddingMode, embeddingBaseUrl, embeddingModel);
  }

  public Snapshot current() {
    Map<String, String> values = new LinkedHashMap<String, String>();
    for (Map<String, Object> row : jdbc.queryForList("SELECT setting_key,setting_value FROM system_setting")) {
      values.put(String.valueOf(row.get("setting_key")), String.valueOf(row.get("setting_value")));
    }
    return new Snapshot(
        values.getOrDefault("ai.mode", defaults.aiMode),
        values.getOrDefault("ai.baseUrl", defaults.aiBaseUrl),
        values.getOrDefault("ai.model", defaults.aiModel),
        values.getOrDefault("embedding.mode", defaults.embeddingMode),
        values.getOrDefault("embedding.baseUrl", defaults.embeddingBaseUrl),
        values.getOrDefault("embedding.model", defaults.embeddingModel));
  }

  public synchronized Snapshot update(ModelSettingsRequest request) {
    Snapshot candidate = fromRequest(request);
    save("ai.mode", candidate.aiMode);
    save("ai.baseUrl", candidate.aiBaseUrl);
    save("ai.model", candidate.aiModel);
    save("embedding.mode", candidate.embeddingMode);
    save("embedding.baseUrl", candidate.embeddingBaseUrl);
    save("embedding.model", candidate.embeddingModel);
    return current();
  }

  public List<Map<String, Object>> discoverModels() throws Exception {
    Snapshot current = current();
    String root = current.aiBaseUrl.replaceFirst("/v1/?$", "");
    ResponseEntity<String> response = http.getForEntity(root + "/api/tags", String.class);
    JsonNode models = mapper.readTree(response.getBody()).path("models");
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (JsonNode model : models) {
      Map<String, Object> item = new LinkedHashMap<String, Object>();
      item.put("name", model.path("name").asText());
      item.put("size", model.path("size").asLong());
      item.put("family", model.path("details").path("family").asText());
      item.put("parameterSize", model.path("details").path("parameter_size").asText());
      result.add(item);
    }
    return result;
  }

  public Map<String, Object> testConnection(ModelSettingsRequest request) throws Exception {
    Snapshot settings = fromRequest(request);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    if (settings.embeddingEnabled()) {
      Map<String, Object> body = new LinkedHashMap<String, Object>();
      body.put("model", settings.embeddingModel);
      List<String> input = new ArrayList<String>();
      input.add("DocPilot connection test");
      body.put("input", input);
      JsonNode root = request(settings.embeddingBaseUrl + "/embeddings", embeddingApiKey, body);
      result.put("embeddingDimensions", root.path("data").path(0).path("embedding").size());
    } else result.put("embeddingDimensions", 0);
    if (settings.aiEnabled()) {
      Map<String, Object> body = new LinkedHashMap<String, Object>();
      body.put("model", settings.aiModel);
      body.put("stream", false);
      List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
      Map<String, String> message = new LinkedHashMap<String, String>();
      message.put("role", "user");
      message.put("content", "Reply with OK");
      messages.add(message);
      body.put("messages", messages);
      JsonNode root = request(settings.aiBaseUrl + "/chat/completions", aiApiKey, body);
      result.put("chatReady", !root.path("choices").path(0).path("message").path("content").asText().isEmpty());
    } else result.put("chatReady", false);
    result.put("success", true);
    return result;
  }

  public String getAiApiKey() { return aiApiKey; }
  public String getEmbeddingApiKey() { return embeddingApiKey; }

  private JsonNode request(String url, String key, Map<String, Object> body) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null && !key.trim().isEmpty()) headers.setBearerAuth(key);
    ResponseEntity<String> response = http.exchange(url, HttpMethod.POST, new HttpEntity<Map<String, Object>>(body, headers), String.class);
    return mapper.readTree(response.getBody());
  }

  private void save(String key, String value) {
    jdbc.update(
        "INSERT INTO system_setting(setting_key,setting_value) VALUES(?,?) "
            + "ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)",
        key,
        value);
  }

  private Snapshot fromRequest(ModelSettingsRequest request) {
    validateMode(request.getAiMode(), "回答模式");
    validateMode(request.getEmbeddingMode(), "检索模式");
    if ("openai".equalsIgnoreCase(request.getAiMode())) validateUrl(request.getAiBaseUrl());
    if ("openai".equalsIgnoreCase(request.getEmbeddingMode())) validateUrl(request.getEmbeddingBaseUrl());
    return new Snapshot(
        request.getAiMode().toLowerCase(), trimSlash(request.getAiBaseUrl()), request.getAiModel().trim(),
        request.getEmbeddingMode().toLowerCase(), trimSlash(request.getEmbeddingBaseUrl()), request.getEmbeddingModel().trim());
  }

  private void validateMode(String mode, String label) {
    if (!"local".equalsIgnoreCase(mode) && !"openai".equalsIgnoreCase(mode)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, label + "不受支持");
    }
  }

  private void validateUrl(String value) {
    if (value == null || !(value.startsWith("http://") || value.startsWith("https://"))) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "模型服务地址必须以 http:// 或 https:// 开头");
    }
  }

  private String trimSlash(String value) {
    String result = value == null ? "" : value.trim();
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }

  public static class Snapshot {
    private final String aiMode, aiBaseUrl, aiModel, embeddingMode, embeddingBaseUrl, embeddingModel;
    Snapshot(String aiMode, String aiBaseUrl, String aiModel, String embeddingMode, String embeddingBaseUrl, String embeddingModel) {
      this.aiMode = aiMode;
      this.aiBaseUrl = aiBaseUrl;
      this.aiModel = aiModel;
      this.embeddingMode = embeddingMode;
      this.embeddingBaseUrl = embeddingBaseUrl;
      this.embeddingModel = embeddingModel;
    }
    public String getAiMode() { return aiMode; }
    public String getAiBaseUrl() { return aiBaseUrl; }
    public String getAiModel() { return aiModel; }
    public String getEmbeddingMode() { return embeddingMode; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public String getEmbeddingModel() { return embeddingModel; }
    public boolean aiEnabled() { return "openai".equalsIgnoreCase(aiMode); }
    public boolean embeddingEnabled() { return "openai".equalsIgnoreCase(embeddingMode); }
    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<String, Object>();
      map.put("aiMode", aiMode);
      map.put("aiBaseUrl", aiBaseUrl);
      map.put("aiModel", aiModel);
      map.put("embeddingMode", embeddingMode);
      map.put("embeddingBaseUrl", embeddingBaseUrl);
      map.put("embeddingModel", embeddingModel);
      return map;
    }
  }
}
