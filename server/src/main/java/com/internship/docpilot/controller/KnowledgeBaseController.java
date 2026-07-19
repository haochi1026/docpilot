package com.internship.docpilot.controller;

import com.internship.docpilot.config.UserPrincipal;
import com.internship.docpilot.dto.CreateKbRequest;
import com.internship.docpilot.dto.KbMemberRequest;
import com.internship.docpilot.model.KnowledgeBase;
import com.internship.docpilot.service.KnowledgeBaseService;
import java.util.*;
import javax.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/kbs")
public class KnowledgeBaseController {
  private final KnowledgeBaseService service;

  public KnowledgeBaseController(KnowledgeBaseService s) {
    service = s;
  }

  @GetMapping
  public List<KnowledgeBase> list(@AuthenticationPrincipal UserPrincipal p) {
    return service.list(p.getUserId(), p.getRole());
  }

  @PostMapping
  public Map<String, Long> create(
      @AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody CreateKbRequest r) {
    return Collections.singletonMap("id", service.create(p.getUserId(), p.getRole(), r));
  }

  @GetMapping("/{id}/members")
  public List<Map<String, Object>> members(
      @AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
    return service.members(id, p.getUserId(), p.getRole());
  }

  @PostMapping("/{id}/members")
  public void grantMember(
      @AuthenticationPrincipal UserPrincipal p,
      @PathVariable Long id,
      @Valid @RequestBody KbMemberRequest request) {
    service.grantMember(id, p.getUserId(), p.getRole(), request);
  }

  @DeleteMapping("/{id}/members/{userId}")
  public void revokeMember(
      @AuthenticationPrincipal UserPrincipal p,
      @PathVariable Long id,
      @PathVariable Long userId) {
    service.revokeMember(id, p.getUserId(), p.getRole(), userId);
  }

  @PostMapping("/{id}/reindex")
  public Map<String, Integer> rebuildIndex(
      @AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) throws Exception {
    return Collections.singletonMap(
        "indexedChunks", service.rebuildIndex(id, p.getUserId(), p.getRole()));
  }

  @PostMapping("/{id}/embedding-migrate")
  public Map<String, Integer> migrateEmbedding(
      @AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) throws Exception {
    return Collections.singletonMap("indexedChunks", service.migrateEmbedding(id, p.getUserId(), p.getRole()));
  }

  @GetMapping("/{id}/index-status")
  public Map<String, Object> indexStatus(
      @AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
    return service.indexStatus(id, p.getUserId(), p.getRole());
  }
}
