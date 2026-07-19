package com.internship.docpilot.controller;

import com.internship.docpilot.config.UserPrincipal;
import com.internship.docpilot.dto.CreateConversationRequest;
import com.internship.docpilot.dto.RenameConversationRequest;
import com.internship.docpilot.service.ConversationService;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
  private final ConversationService conversations;

  public ConversationController(ConversationService conversations) {
    this.conversations = conversations;
  }

  @GetMapping
  public List<Map<String, Object>> list(
      @AuthenticationPrincipal UserPrincipal principal, @RequestParam Long kbId) {
    return conversations.list(principal.getUserId(), principal.getRole(), kbId);
  }

  @PostMapping
  public Map<String, Object> create(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody CreateConversationRequest request) {
    return conversations.create(principal.getUserId(), principal.getRole(), request);
  }

  @GetMapping("/{id}/messages")
  public List<Map<String, Object>> messages(
      @AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
    return conversations.messages(principal.getUserId(), principal.getRole(), id);
  }

  @PatchMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void rename(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable Long id,
      @Valid @RequestBody RenameConversationRequest request) {
    conversations.rename(principal.getUserId(), id, request.getTitle());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
    conversations.delete(principal.getUserId(), id);
  }
}
