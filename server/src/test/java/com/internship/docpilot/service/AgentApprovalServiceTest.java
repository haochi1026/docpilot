package com.internship.docpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.repository.AgentApprovalRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentApprovalServiceTest {
  @Test
  void approvesRowsWhoseMysqlDatetimeIsMappedAsLocalDateTime() {
    AgentApprovalRepository repository = mock(AgentApprovalRepository.class);
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("id", "approval-123");
    row.put("user_id", 3L);
    row.put("conversation_id", 8L);
    row.put("kb_id", 2L);
    row.put("thread_id", "docpilot-8");
    row.put("tool_name", "retry_document_parsing");
    row.put("resource_id", 19L);
    row.put("status", "PENDING");
    row.put("expires_at", LocalDateTime.now().plusMinutes(10));
    when(repository.owned("approval-123", 3L, 8L)).thenReturn(row);
    when(repository.decide("approval-123", "PENDING", "APPROVED")).thenAnswer(
        invocation -> {
          row.put("status", "APPROVED");
          return 1;
        });
    AgentApprovalService service =
        new AgentApprovalService(repository, new ObjectMapper(), "test-secret", 15);

    AgentApprovalService.Decision decision =
        service.decide(3L, 8L, "approval-123", "approve");

    assertEquals("approval-123", decision.getApprovalId());
    assertNotNull(decision.getToken());
  }
}
