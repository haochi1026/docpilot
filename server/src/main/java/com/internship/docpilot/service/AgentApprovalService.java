package com.internship.docpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.repository.AgentApprovalRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentApprovalService {
  private static final String RETRY_TOOL = "retry_document_parsing";
  private final AgentApprovalRepository approvals;
  private final ObjectMapper mapper;
  private final byte[] secret;
  private final int ttlMinutes;

  public AgentApprovalService(
      AgentApprovalRepository approvals,
      ObjectMapper mapper,
      @Value("${app.agent.approval-secret}") String secret,
      @Value("${app.agent.approval-ttl-minutes:15}") int ttlMinutes) {
    this.approvals = approvals;
    this.mapper = mapper;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.ttlMinutes = Math.max(1, ttlMinutes);
  }

  public Map<String, Object> create(
      Long userId,
      Long conversationId,
      Long kbId,
      String threadId,
      Object interruptPayload) {
    Action action = action(interruptPayload);
    Map<String, Object> existing =
        approvals.pendingByThread(userId, conversationId, threadId, action.toolName, action.resourceId);
    if (existing != null) return view(existing);
    if (!RETRY_TOOL.equals(action.toolName)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "该 Agent 写操作不在允许的审批范围内");
    }
    String id = UUID.randomUUID().toString();
    LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttlMinutes);
    try {
      approvals.create(
          id,
          userId,
          conversationId,
          kbId,
          threadId,
          action.toolName,
          action.resourceId,
          mapper.writeValueAsString(interruptPayload),
          expiresAt);
    } catch (Exception error) {
      throw new IllegalStateException("无法持久化 Agent 审批任务", error);
    }
    return view(approvals.owned(id, userId, conversationId));
  }

  public Decision decide(
      Long userId, Long conversationId, String approvalId, String decision) {
    Map<String, Object> row = approvals.owned(approvalId, userId, conversationId);
    requireActive(row);
    String status = string(row, "status");
    if ("reject".equals(decision)) {
      if ("REJECTED".equals(status)) {
        // The decision is durable but LangGraph resume may have failed after
        // the database commit.  Reusing the same decision is safe and lets the
        // caller finish the interrupted checkpoint without creating a new
        // approval record.
        return new Decision(approvalId, null);
      }
      if (!"PENDING".equals(status) || approvals.decide(approvalId, "PENDING", "REJECTED") != 1) {
        throw new BusinessException(HttpStatus.CONFLICT, "审批任务已处理，不能重复拒绝");
      }
      return new Decision(approvalId, null);
    }
    if (!"approve".equals(decision)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "无效的审批决定");
    }
    if ("PENDING".equals(status)) {
      if (approvals.decide(approvalId, "PENDING", "APPROVED") != 1) {
        throw new BusinessException(HttpStatus.CONFLICT, "审批状态已变化，请刷新后重试");
      }
      row = approvals.owned(approvalId, userId, conversationId);
    } else if (!"APPROVED".equals(status)) {
      throw new BusinessException(HttpStatus.CONFLICT, "审批任务已结束");
    }
    return new Decision(approvalId, token(row));
  }

  public Map<String, Object> pending(Long userId, Long conversationId) {
    Map<String, Object> row = approvals.pending(userId, conversationId);
    return row == null ? null : view(row);
  }

  public void markRejectedResumed(Long userId, Long conversationId, String approvalId) {
    Map<String, Object> row = approvals.owned(approvalId, userId, conversationId);
    if (row == null) return;
    if ("REJECTED_RESUMED".equals(string(row, "status"))) return;
    if (approvals.markRejectedResumed(approvalId) != 1) {
      throw new BusinessException(HttpStatus.CONFLICT, "拒绝决定的 Agent 恢复状态已变化");
    }
  }

  public void consume(
      Long userId,
      String approvalId,
      String suppliedToken,
      String toolName,
      Long resourceId) {
    Map<String, Object> row = approvals.byUser(approvalId, userId);
    requireActive(row);
    if (!"APPROVED".equals(string(row, "status"))) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "写操作尚未获得人工批准");
    }
    if (!toolName.equals(string(row, "tool_name"))
        || !resourceId.equals(number(row, "resource_id"))) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "审批内容与实际工具参数不一致");
    }
    String expected = token(row);
    if (suppliedToken == null
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            suppliedToken.getBytes(StandardCharsets.UTF_8))) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "审批凭证无效或已被篡改");
    }
    if (approvals.consume(approvalId) != 1) {
      throw new BusinessException(HttpStatus.CONFLICT, "审批凭证已被使用或已经过期");
    }
  }

  public void deleteConversation(Long conversationId, Long userId) {
    approvals.deleteConversation(conversationId, userId);
  }

  private void requireActive(Map<String, Object> row) {
    if (row == null) throw new BusinessException(HttpStatus.NOT_FOUND, "审批任务不存在或无权访问");
    LocalDateTime expires = dateTime(row.get("expires_at"));
    if (expires == null || expires.isBefore(LocalDateTime.now())) {
      approvals.expire(string(row, "id"));
      throw new BusinessException(HttpStatus.CONFLICT, "审批任务已过期");
    }
  }

  private Map<String, Object> view(Map<String, Object> row) {
    if (row == null) return null;
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("approvalId", string(row, "id"));
    result.put("conversationId", number(row, "conversation_id"));
    result.put("kbId", number(row, "kb_id"));
    result.put("threadId", string(row, "thread_id"));
    result.put("toolName", string(row, "tool_name"));
    result.put("resourceId", number(row, "resource_id"));
    result.put("status", string(row, "status"));
    result.put("expiresAt", row.get("expires_at"));
    try {
      result.put("interrupts", mapper.readValue(string(row, "action_payload"), Object.class));
    } catch (Exception error) {
      result.put("interrupts", null);
    }
    return result;
  }

  private Action action(Object payload) {
    JsonNode root = mapper.valueToTree(payload);
    JsonNode actions = find(root, "action_requests");
    if (actions == null || !actions.isArray() || actions.size() != 1) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "当前仅支持单个可审计写操作");
    }
    JsonNode action = actions.get(0);
    String name = action.path("name").asText();
    JsonNode args = action.has("args") ? action.path("args") : action.path("arguments");
    long resourceId = args.path("document_id").asLong(0);
    if (resourceId <= 0) throw new BusinessException(HttpStatus.BAD_REQUEST, "审批操作缺少 document_id");
    return new Action(name, resourceId);
  }

  private JsonNode find(JsonNode node, String field) {
    if (node == null) return null;
    if (node.has(field)) return node.get(field);
    if (node.isArray()) {
      for (JsonNode child : node) {
        JsonNode found = find(child, field);
        if (found != null) return found;
      }
    } else if (node.isObject()) {
      java.util.Iterator<JsonNode> children = node.elements();
      while (children.hasNext()) {
        JsonNode found = find(children.next(), field);
        if (found != null) return found;
      }
    }
    return null;
  }

  private String token(Map<String, Object> row) {
    String payload =
        string(row, "id")
            + "|"
            + number(row, "user_id")
            + "|"
            + string(row, "thread_id")
            + "|"
            + string(row, "tool_name")
            + "|"
            + number(row, "resource_id")
            + "|"
            + dateTime(row.get("expires_at"));
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("无法生成审批凭证", error);
    }
  }

  private String string(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  private Long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private LocalDateTime dateTime(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime) return (LocalDateTime) value;
    if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toLocalDateTime();
    if (value instanceof java.time.OffsetDateTime)
      return ((java.time.OffsetDateTime) value).toLocalDateTime();
    if (value instanceof java.util.Date)
      return LocalDateTime.ofInstant(
          ((java.util.Date) value).toInstant(), java.time.ZoneId.systemDefault());
    return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
  }

  private static class Action {
    private final String toolName;
    private final Long resourceId;

    Action(String toolName, Long resourceId) {
      this.toolName = toolName;
      this.resourceId = resourceId;
    }
  }

  public static class Decision {
    private final String approvalId;
    private final String token;

    Decision(String approvalId, String token) {
      this.approvalId = approvalId;
      this.token = token;
    }

    public String getApprovalId() {
      return approvalId;
    }

    public String getToken() {
      return token;
    }
  }
}
