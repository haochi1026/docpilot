package com.internship.docpilot.controller;

import com.internship.docpilot.config.UserPrincipal;
import com.internship.docpilot.service.WorkspaceService;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {
  private final WorkspaceService service;

  public WorkspaceController(WorkspaceService service) {
    this.service = service;
  }

  @GetMapping("/overview")
  public Map<String, Object> overview(
      @AuthenticationPrincipal UserPrincipal principal, @RequestParam Long kbId) {
    return service.overview(principal.getUserId(), principal.getRole(), kbId);
  }
}
