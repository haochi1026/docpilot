package com.internship.docpilot.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentApprovalRepository {
  private final JdbcTemplate jdbc;

  public AgentApprovalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void create(
      String id,
      Long userId,
      Long conversationId,
      Long kbId,
      String threadId,
      String toolName,
      Long resourceId,
      String payload,
      LocalDateTime expiresAt) {
    jdbc.update(
        "UPDATE agent_approval SET status='EXPIRED' WHERE user_id=? AND conversation_id=? "
            + "AND status='PENDING'",
        userId,
        conversationId);
    jdbc.update(
        "INSERT INTO agent_approval(id,user_id,conversation_id,kb_id,thread_id,tool_name,resource_id,"
            + "action_payload,status,expires_at) VALUES(?,?,?,?,?,?,?,?,'PENDING',?)",
        id,
        userId,
        conversationId,
        kbId,
        threadId,
        toolName,
        resourceId,
        payload,
        expiresAt);
  }

  public Map<String, Object> owned(String id, Long userId, Long conversationId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT * FROM agent_approval WHERE id=? AND user_id=? AND conversation_id=?",
            id,
            userId,
            conversationId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> byUser(String id, Long userId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT * FROM agent_approval WHERE id=? AND user_id=?", id, userId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> pending(Long userId, Long conversationId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT * FROM agent_approval WHERE user_id=? AND conversation_id=? "
                + "AND status IN('PENDING','APPROVED','REJECTED') AND expires_at>NOW() "
                + "ORDER BY created_at DESC LIMIT 1",
            userId,
            conversationId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> pendingByThread(
      Long userId, Long conversationId, String threadId, String toolName, Long resourceId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT * FROM agent_approval WHERE user_id=? AND conversation_id=? AND thread_id=? "
                + "AND tool_name=? AND resource_id=? AND status IN('PENDING','APPROVED','REJECTED') AND expires_at>NOW() "
                + "ORDER BY created_at DESC LIMIT 1",
            userId,
            conversationId,
            threadId,
            toolName,
            resourceId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public int decide(String id, String expectedStatus, String targetStatus) {
    return jdbc.update(
        "UPDATE agent_approval SET status=?,decided_at=NOW() WHERE id=? AND status=? AND expires_at>NOW()",
        targetStatus,
        id,
        expectedStatus);
  }

  public int consume(String id) {
    return jdbc.update(
        "UPDATE agent_approval SET status='CONSUMED',consumed_at=NOW() "
            + "WHERE id=? AND status='APPROVED' AND consumed_at IS NULL AND expires_at>NOW()",
        id);
  }

  public int markRejectedResumed(String id) {
    return jdbc.update(
        "UPDATE agent_approval SET status='REJECTED_RESUMED',consumed_at=NOW() "
            + "WHERE id=? AND status='REJECTED' AND expires_at>NOW()",
        id);
  }

  public void expire(String id) {
    jdbc.update(
        "UPDATE agent_approval SET status='EXPIRED' WHERE id=? AND status IN('PENDING','APPROVED','REJECTED')",
        id);
  }

  public void deleteConversation(Long conversationId, Long userId) {
    jdbc.update(
        "DELETE FROM agent_approval WHERE conversation_id=? AND user_id=?", conversationId, userId);
  }
}
