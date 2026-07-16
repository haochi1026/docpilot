package com.internship.docpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.model.SearchHit;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class OpenAiCompatibleAnswerService implements AiAnswerService {
  private final ModelSettingsService settings;
  private final RestTemplate http = new RestTemplate();
  private final ObjectMapper mapper = new ObjectMapper();

  public OpenAiCompatibleAnswerService(ModelSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public String answer(String question, List<SearchHit> hits) throws Exception {
    return blockingAnswer(question, hits, Collections.emptyList());
  }

  @Override
  public String answer(
      String question,
      List<SearchHit> hits,
      List<Map<String, String>> history,
      Consumer<String> tokenConsumer)
      throws Exception {
    ModelSettingsService.Snapshot config = settings.current();
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("model", config.getAiModel());
    body.put("temperature", 0.2);
    body.put("stream", true);
    body.put("messages", buildMessages(question, hits, history));

    StringBuilder answer = new StringBuilder();
    try {
      http.execute(
          config.getAiBaseUrl() + "/chat/completions",
          HttpMethod.POST,
          request -> {
            HttpHeaders headers = request.getHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String apiKey = settings.getAiApiKey();
            if (apiKey != null && !apiKey.trim().isEmpty()) headers.setBearerAuth(apiKey);
            mapper.writeValue(request.getBody(), body);
          },
          response -> {
            try (BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
              String line;
              while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                String token = extractToken(data);
                if (!token.isEmpty()) {
                  answer.append(token);
                  if (tokenConsumer != null) tokenConsumer.accept(token);
                }
              }
            }
            return null;
          });
    } catch (Exception streamingError) {
      if (answer.length() > 0) throw streamingError;
    }

    if (answer.length() > 0) return answer.toString();

    String fallback =
        blockingAnswer(question, hits, history == null ? Collections.emptyList() : history);
    if (tokenConsumer != null && !fallback.isEmpty()) tokenConsumer.accept(fallback);
    return fallback;
  }

  private String blockingAnswer(
      String question, List<SearchHit> hits, List<Map<String, String>> history) throws Exception {
    ModelSettingsService.Snapshot config = settings.current();
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("model", config.getAiModel());
    body.put("temperature", 0.2);
    body.put("stream", false);
    body.put("messages", buildMessages(question, hits, history));

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String apiKey = settings.getAiApiKey();
    if (apiKey != null && !apiKey.trim().isEmpty()) headers.setBearerAuth(apiKey);
    ResponseEntity<String> response =
        http.exchange(
            config.getAiBaseUrl() + "/chat/completions",
            HttpMethod.POST,
            new HttpEntity<Map<String, Object>>(body, headers),
            String.class);
    JsonNode root = mapper.readTree(response.getBody());
    return root.path("choices").path(0).path("message").path("content").asText();
  }

  private List<Map<String, String>> buildMessages(
      String question, List<SearchHit> hits, List<Map<String, String>> history) {
    StringBuilder context = new StringBuilder();
    for (int i = 0; i < hits.size(); i++) {
      context
          .append("[来源")
          .append(i + 1)
          .append("] ")
          .append(hits.get(i).getContent())
          .append("\n");
    }

    List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
    messages.add(
        msg(
            "system",
            "你是私有知识库问答助手。只能依据给定资料回答；资料不足时要明确说明，"
                + "并在关键结论后使用[来源N]标注依据。历史对话只用于理解追问，不得替代资料证据。"));
    if (history != null) {
      for (Map<String, String> item : history) {
        String role = item.get("role");
        String content = item.get("content");
        if (role == null || content == null || content.trim().isEmpty()) continue;
        if (!"user".equals(role) && !"assistant".equals(role)) continue;
        messages.add(msg(role, truncate(content, 800)));
      }
    }
    messages.add(msg("user", "资料：\n" + context + "\n问题：" + question));
    return messages;
  }

  private String extractToken(String data) throws IOException {
    JsonNode root = mapper.readTree(data);
    JsonNode choice = root.path("choices").path(0);
    String token = choice.path("delta").path("content").asText("");
    if (token.isEmpty()) token = choice.path("message").path("content").asText("");
    return token;
  }

  private String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max) + "...";
  }

  private Map<String, String> msg(String role, String content) {
    Map<String, String> message = new HashMap<String, String>();
    message.put("role", role);
    message.put("content", content);
    return message;
  }
}
