package com.internship.docpilot.controller;

import com.internship.docpilot.config.UserPrincipal;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.repository.OutboxRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/outbox")
public class OutboxAdminController {
  private final OutboxRepository outbox;

  public OutboxAdminController(OutboxRepository outbox) { this.outbox = outbox; }

  @GetMapping("/metrics")
  public Map<String, Object> metrics(@AuthenticationPrincipal UserPrincipal principal) {
    requireAdmin(principal);
    Map<String, Object> value = new LinkedHashMap<String, Object>();
    value.put("open", outbox.pendingCount());
    value.put("deadLetter", outbox.deadCount());
    value.put("oldestOpenAgeSeconds", outbox.oldestOpenAgeSeconds());
    return value;
  }

  @PostMapping("/{id}/replay")
  public Map<String, Object> replay(
      @AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
    requireAdmin(principal);
    if (outbox.replayDead(id) != 1) {
      throw new BusinessException(HttpStatus.CONFLICT, "消息不存在或当前不是死信状态");
    }
    return java.util.Collections.<String, Object>singletonMap("replayed", id);
  }

  private void requireAdmin(UserPrincipal principal) {
    if (principal == null || !"ADMIN".equals(principal.getRole())) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "仅平台管理员可以操作Outbox");
    }
  }
}
