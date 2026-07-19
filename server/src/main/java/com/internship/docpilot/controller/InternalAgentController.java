package com.internship.docpilot.controller;

import com.internship.docpilot.dto.AgentSearchRequest;
import com.internship.docpilot.model.DocumentView;
import com.internship.docpilot.model.KnowledgeBase;
import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.service.AgentToolService;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/agent")
public class InternalAgentController {
  private final AgentToolService tools;

  public InternalAgentController(AgentToolService tools) {
    this.tools = tools;
  }

  @GetMapping("/knowledge-bases")
  public List<KnowledgeBase> knowledgeBases(
      @RequestHeader("X-Agent-Key") String key,
      @RequestHeader("X-Username") String username) {
    return tools.listKnowledgeBases(key, username);
  }

  @PostMapping("/search")
  public List<SearchHit> search(
      @RequestHeader("X-Agent-Key") String key,
      @RequestHeader("X-Username") String username,
      @Valid @RequestBody AgentSearchRequest request) {
    return tools.search(key, username, request);
  }

  @GetMapping("/chunks/{id}")
  public Map<String, Object> chunk(
      @RequestHeader("X-Agent-Key") String key,
      @RequestHeader("X-Username") String username,
      @PathVariable Long id) {
    return tools.chunk(key, username, id);
  }

  @GetMapping("/knowledge-bases/{kbId}/documents")
  public List<DocumentView> documents(
      @RequestHeader("X-Agent-Key") String key,
      @RequestHeader("X-Username") String username,
      @PathVariable Long kbId) {
    return tools.listDocuments(key, username, kbId);
  }

  @GetMapping("/documents/{id}")
  public Map<String, Object> document(
      @RequestHeader("X-Agent-Key") String key,
      @RequestHeader("X-Username") String username,
      @PathVariable Long id) {
    return tools.documentDiagnostics(key, username, id);
  }

  @PostMapping("/documents/{id}/retry")
  public Map<String, Object> retryDocument(
      @RequestHeader("X-Agent-Key") String key,
      @RequestHeader("X-Username") String username,
      @RequestHeader(value = "X-Agent-Approval-Id", required = false) String approvalId,
      @RequestHeader(value = "X-Agent-Approval-Token", required = false) String approvalToken,
      @PathVariable Long id) {
    return tools.retryDocument(key, username, approvalId, approvalToken, id);
  }
}
