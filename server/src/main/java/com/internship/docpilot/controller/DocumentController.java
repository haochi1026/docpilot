package com.internship.docpilot.controller;

import com.internship.docpilot.config.UserPrincipal;
import com.internship.docpilot.model.DocumentView;
import com.internship.docpilot.service.DocumentService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
  private final DocumentService service;

  public DocumentController(DocumentService s) {
    service = s;
  }

  @GetMapping
  public List<DocumentView> list(
      @AuthenticationPrincipal UserPrincipal p, @RequestParam Long kbId) {
    return service.list(p.getUserId(), p.getRole(), kbId);
  }

  @PostMapping
  public DocumentView upload(
      @AuthenticationPrincipal UserPrincipal p,
      @RequestParam Long kbId,
      @RequestPart("file") MultipartFile file)
      throws Exception {
    return service.upload(p.getUserId(), p.getRole(), kbId, file);
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(
      @AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
    return service.detail(p.getUserId(), p.getRole(), id);
  }

  @PostMapping("/{id}/retry")
  public ResponseEntity<Void> retry(
      @AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
    service.retry(p.getUserId(), p.getRole(), id);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) throws Exception {
    service.delete(p.getUserId(), p.getRole(), id);
    return ResponseEntity.noContent().build();
  }
}
