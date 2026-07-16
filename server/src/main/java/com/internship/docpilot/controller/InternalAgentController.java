package com.internship.docpilot.controller;

import com.internship.docpilot.dto.AgentSearchRequest;
import com.internship.docpilot.model.KnowledgeBase;
import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.service.AgentToolService;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
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
}

