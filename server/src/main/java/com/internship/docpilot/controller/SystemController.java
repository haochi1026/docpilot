package com.internship.docpilot.controller;

import com.internship.docpilot.config.UserPrincipal;
import com.internship.docpilot.dto.ModelSettingsRequest;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.service.EmbeddingService;
import com.internship.docpilot.service.ModelSettingsService;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final String queueMode;
  private final boolean agentEnabled;
  private final EmbeddingService embeddings;
  private final ModelSettingsService settings;

  public SystemController(
      @Value("${app.queue.mode:local}") String queueMode,
      @Value("${app.agent.enabled:false}") boolean agentEnabled,
      EmbeddingService embeddings,
      ModelSettingsService settings) {
    this.queueMode = queueMode;
    this.agentEnabled = agentEnabled;
    this.embeddings = embeddings;
    this.settings = settings;
  }

  @GetMapping("/capabilities")
  public Map<String, Object> capabilities() {
    ModelSettingsService.Snapshot config = settings.current();
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("aiMode", config.getAiMode());
    result.put("aiModel", config.aiEnabled() ? config.getAiModel() : "未接入大模型");
    result.put("embeddingMode", embeddings.getMode());
    result.put(
        "embeddingModel", embeddings.enabled() ? embeddings.getModel() : "未使用 Embedding");
    result.put(
        "retrievalMode", embeddings.enabled() ? "向量 + 词片混合检索" : "字符词片检索");
    result.put(
        "answerMode",
        agentEnabled
            ? "LangChain / LangGraph Agent"
            : (config.aiEnabled() ? "大模型归纳回答" : "本地抽取式回答"));
    result.put("agentEnabled", agentEnabled);
    result.put("queueMode", queueMode);
    return result;
  }

  @GetMapping("/settings")
  public Map<String, Object> settings(@AuthenticationPrincipal UserPrincipal principal) {
    requireAdmin(principal);
    return settings.current().toMap();
  }

  @PutMapping("/settings")
  public Map<String, Object> update(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestBody ModelSettingsRequest request) {
    requireAdmin(principal);
    return settings.update(request).toMap();
  }

  @GetMapping("/models")
  public List<Map<String, Object>> models(@AuthenticationPrincipal UserPrincipal principal)
      throws Exception {
    requireAdmin(principal);
    return settings.discoverModels();
  }

  @PostMapping("/test-models")
  public Map<String, Object> test(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestBody ModelSettingsRequest request)
      throws Exception {
    requireAdmin(principal);
    return settings.testConnection(request);
  }

  private void requireAdmin(UserPrincipal principal) {
    if (principal == null || !"ADMIN".equals(principal.getRole())) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "仅平台管理员可以修改模型配置");
    }
  }
}
