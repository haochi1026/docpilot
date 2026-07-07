package com.internship.docpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.model.SearchHit;
import java.util.*;
import org.springframework.http.*;
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

  public String answer(String q, List<SearchHit> hits) throws Exception {
    ModelSettingsService.Snapshot config = settings.current();
    StringBuilder ctx = new StringBuilder();
    for (int i = 0; i < hits.size(); i++)
      ctx.append("[来源").append(i + 1).append("] ").append(hits.get(i).getContent()).append("\n");
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("model", config.getAiModel());
    body.put("temperature", 0.2);
    body.put("stream", false);
    List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
    messages.add(msg("system", "你是私有知识库问答助手。只能依据给定资料回答；资料不足时明确说明，并用[来源N]标注依据。"));
    messages.add(msg("user", "资料：\n" + ctx + "\n问题：" + q));
    body.put("messages", messages);
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    if (!settings.getAiApiKey().trim().isEmpty()) h.setBearerAuth(settings.getAiApiKey());
    ResponseEntity<String> r =
        http.exchange(
            config.getAiBaseUrl() + "/chat/completions",
            HttpMethod.POST,
            new HttpEntity<Map<String, Object>>(body, h),
            String.class);
    JsonNode root = mapper.readTree(r.getBody());
    return root.path("choices").path(0).path("message").path("content").asText();
  }

  private Map<String, String> msg(String role, String content) {
    Map<String, String> x = new HashMap<String, String>();
    x.put("role", role);
    x.put("content", content);
    return x;
  }
}
