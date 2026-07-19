package com.internship.docpilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.model.SearchHit;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentClient {
  private final ObjectMapper mapper;
  private final boolean enabled;
  private final String baseUrl;
  private final String serviceKey;
  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  public AgentClient(
      ObjectMapper mapper,
      @Value("${app.agent.enabled:false}") boolean enabled,
      @Value("${app.agent.base-url:http://localhost:8090}") String baseUrl,
      @Value("${app.agent.service-key:change-this-agent-service-key}") String serviceKey,
      @Value("${app.agent.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${app.agent.read-timeout-ms:180000}") int readTimeoutMs) {
    this.mapper = mapper;
    this.enabled = enabled;
    this.baseUrl = baseUrl;
    this.serviceKey = serviceKey;
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public RunResult stream(
      Long userId,
      String username,
      String role,
      Long kbId,
      Long conversationId,
      String message,
      String decision,
      String approvalId,
      String approvalToken,
      List<Map<String, String>> history,
      SseEmitter emitter)
      throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(trimSlash(baseUrl) + "/v1/agent/chat/stream").openConnection();
    connection.setRequestMethod("POST");
    connection.setConnectTimeout(connectTimeoutMs);
    connection.setReadTimeout(readTimeoutMs);
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
    connection.setRequestProperty("Accept", "application/x-ndjson");
    connection.setRequestProperty("X-Agent-Service-Key", serviceKey);

    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("request_id", UUID.randomUUID().toString());
    payload.put("thread_id", "docpilot-" + conversationId);
    payload.put("username", username);
    payload.put("user_id", userId);
    payload.put("role", role);
    payload.put("kb_id", kbId);
    if (decision == null) payload.put("message", message);
    else {
      payload.put("decision", decision);
      payload.put("approval_id", approvalId);
      if (approvalToken != null) payload.put("approval_token", approvalToken);
    }
    payload.put("history", history == null ? new ArrayList<Map<String, String>>() : history);

    byte[] body = mapper.writeValueAsBytes(payload);
    connection.setFixedLengthStreamingMode(body.length);
    try (OutputStream output = connection.getOutputStream()) {
      output.write(body);
    }

    int status = connection.getResponseCode();
    InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    if (status >= 400) {
      throw new IllegalStateException("Agent 服务返回 HTTP " + status + ": " + readText(input));
    }

    RunResult result = new RunResult();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.trim().isEmpty()) continue;
        JsonNode event = mapper.readTree(line);
        String type = event.path("type").asText();
        JsonNode data = event.get("data");
        if ("status".equals(type) || "token".equals(type) || "replace".equals(type)) {
          emitter.send(SseEmitter.event().name(type).data(data == null ? "" : data.asText()));
        } else if ("sources".equals(type)) {
          result.sources = mapper.convertValue(data, new TypeReference<List<SearchHit>>() {});
          emitter.send(SseEmitter.event().name("sources").data(result.sources));
        } else if ("approval".equals(type)) {
          result.interrupted = true;
          result.approval = mapper.convertValue(data, Object.class);
        } else if ("done".equals(type)) {
          result.answer = data == null ? "" : data.path("answer").asText("");
          if ((result.sources == null || result.sources.isEmpty()) && data != null) {
            result.sources =
                mapper.convertValue(data.path("sources"), new TypeReference<List<SearchHit>>() {});
          }
          result.completed = true;
        } else if ("error".equals(type)) {
          throw new IllegalStateException(data == null ? "Agent 执行失败" : data.asText());
        }
      }
    } finally {
      connection.disconnect();
    }
    if (!result.interrupted && !result.completed) {
      throw new IllegalStateException("Agent 流提前结束，未收到完成事件");
    }
    return result;
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String readText(InputStream input) throws Exception {
    if (input == null) return "";
    StringBuilder text = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) text.append(line);
    }
    return text.toString();
  }

  public static class RunResult {
    private String answer = "";
    private List<SearchHit> sources = new ArrayList<SearchHit>();
    private boolean interrupted;
    private boolean completed;
    private Object approval;

    public String getAnswer() {
      return answer;
    }

    public List<SearchHit> getSources() {
      return sources;
    }

    public boolean isInterrupted() {
      return interrupted;
    }

    public Object getApproval() {
      return approval;
    }
  }

  public void deleteThread(Long conversationId) {
    if (!enabled || conversationId == null) return;
    HttpURLConnection connection = null;
    try {
      connection =
          (HttpURLConnection)
              new URL(trimSlash(baseUrl) + "/v1/agent/threads/docpilot-" + conversationId)
                  .openConnection();
      connection.setRequestMethod("DELETE");
      connection.setConnectTimeout(connectTimeoutMs);
      connection.setReadTimeout(connectTimeoutMs);
      connection.setRequestProperty("X-Agent-Service-Key", serviceKey);
      connection.getResponseCode();
    } catch (Exception ignored) {
    } finally {
      if (connection != null) connection.disconnect();
    }
  }
}
