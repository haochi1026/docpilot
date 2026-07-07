package com.internship.docpilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.dto.CreateConversationRequest;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.repository.ConversationRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
  private final ConversationRepository conversations;
  private final KnowledgeBaseService knowledgeBases;
  private final ObjectMapper mapper = new ObjectMapper();

  public ConversationService(ConversationRepository conversations, KnowledgeBaseService knowledgeBases) {
    this.conversations = conversations;
    this.knowledgeBases = knowledgeBases;
  }

  public List<Map<String, Object>> list(Long userId, String role, Long kbId) {
    knowledgeBases.requireRead(kbId, userId, role);
    return conversations.list(kbId, userId);
  }

  public Map<String, Object> create(Long userId, String role, CreateConversationRequest request) {
    knowledgeBases.requireRead(request.getKbId(), userId, role);
    String title = cleanTitle(request.getTitle());
    Long id = conversations.create(request.getKbId(), userId, title);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("id", id);
    result.put("kbId", request.getKbId());
    result.put("title", title);
    result.put("messageCount", 0);
    return result;
  }

  public List<Map<String, Object>> messages(Long userId, String role, Long conversationId) {
    Map<String, Object> conversation = requireOwned(conversationId, userId);
    knowledgeBases.requireRead(number(conversation.get("kb_id")), userId, role);
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> row : conversations.messages(conversationId)) {
      Map<String, Object> item = new LinkedHashMap<String, Object>(row);
      String sourcesJson = String.valueOf(item.remove("sourcesJson"));
      if ("null".equals(sourcesJson) || sourcesJson.trim().isEmpty()) {
        item.put("sources", Collections.emptyList());
      } else {
        try {
          item.put("sources", mapper.readValue(sourcesJson, new TypeReference<List<Map<String, Object>>>() {}));
        } catch (Exception ignored) {
          item.put("sources", Collections.emptyList());
        }
      }
      result.add(item);
    }
    return result;
  }

  public void rename(Long userId, Long conversationId, String title) {
    requireOwned(conversationId, userId);
    conversations.rename(conversationId, userId, cleanTitle(title));
  }

  @Transactional
  public void delete(Long userId, Long conversationId) {
    requireOwned(conversationId, userId);
    conversations.delete(conversationId, userId);
  }

  public Long ensure(Long userId, String role, Long kbId, Long conversationId) {
    knowledgeBases.requireRead(kbId, userId, role);
    if (conversationId == null) {
      CreateConversationRequest request = new CreateConversationRequest();
      request.setKbId(kbId);
      request.setTitle("新对话");
      return ((Number) create(userId, role, request).get("id")).longValue();
    }
    Map<String, Object> conversation = requireOwned(conversationId, userId);
    if (!kbId.equals(number(conversation.get("kb_id")))) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "对话不属于当前知识库");
    }
    return conversationId;
  }

  public void addUserMessage(Long conversationId, Long userId, String question) {
    Map<String, Object> conversation = requireOwned(conversationId, userId);
    if (conversations.messageCount(conversationId) == 0 && "新对话".equals(String.valueOf(conversation.get("title")))) {
      conversations.rename(conversationId, userId, titleFromQuestion(question));
    }
    conversations.addMessage(conversationId, "user", question, null);
  }

  public void addAssistantMessage(Long conversationId, Long userId, String answer, Object sources) throws Exception {
    requireOwned(conversationId, userId);
    conversations.addMessage(conversationId, "assistant", answer, mapper.writeValueAsString(sources));
  }

  private Map<String, Object> requireOwned(Long id, Long userId) {
    Map<String, Object> conversation = conversations.owned(id, userId);
    if (conversation == null) throw new BusinessException(HttpStatus.NOT_FOUND, "对话不存在或无权访问");
    return conversation;
  }

  private Long number(Object value) {
    return ((Number) value).longValue();
  }

  private String cleanTitle(String value) {
    String title = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    if (title.isEmpty()) title = "新对话";
    return title.length() > 80 ? title.substring(0, 80) : title;
  }

  private String titleFromQuestion(String question) {
    String title = question.replaceAll("\\s+", " ").trim();
    return title.length() > 28 ? title.substring(0, 28) + "…" : title;
  }
}
