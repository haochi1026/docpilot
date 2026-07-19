package com.internship.docpilot.controller;

import com.internship.docpilot.config.UserPrincipal;
import com.internship.docpilot.dto.AgentResumeRequest;
import com.internship.docpilot.dto.ChatRequest;
import com.internship.docpilot.service.ChatService;
import javax.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
  private final ChatService service;

  public ChatController(ChatService s) {
    service = s;
  }

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody ChatRequest r) {
    return service.chat(p.getUserId(), p.getUsername(), p.getRole(), r);
  }

  @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter resume(
      @AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody AgentResumeRequest r) {
    return service.resume(p.getUserId(), p.getUsername(), p.getRole(), r);
  }

  @GetMapping("/approvals/pending")
  public Map<String, Object> pendingApproval(
      @AuthenticationPrincipal UserPrincipal p, @RequestParam Long conversationId) {
    return service.pendingApproval(p.getUserId(), p.getRole(), conversationId);
  }
}
