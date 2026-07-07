package com.internship.docpilot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseService knowledgeBases;

  public WorkspaceService(JdbcTemplate jdbc, KnowledgeBaseService knowledgeBases) {
    this.jdbc = jdbc;
    this.knowledgeBases = knowledgeBases;
  }

  public Map<String, Object> overview(Long userId, String role, Long kbId) {
    knowledgeBases.requireRead(kbId, userId, role);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    int total = count("SELECT COUNT(*) FROM document WHERE kb_id=?", kbId);
    int ready = count("SELECT COUNT(*) FROM document WHERE kb_id=? AND status='SUCCESS'", kbId);
    result.put("documentCount", total);
    result.put("readyCount", ready);
    result.put(
        "processingCount",
        count(
            "SELECT COUNT(*) FROM document WHERE kb_id=? AND status IN('PENDING','PROCESSING')",
            kbId));
    result.put("failedCount", count("SELECT COUNT(*) FROM document WHERE kb_id=? AND status='FAILED'", kbId));
    result.put(
        "chunkCount",
        count(
            "SELECT COUNT(*) FROM document_chunk c JOIN document d ON d.id=c.document_id WHERE d.kb_id=?",
            kbId));
    result.put(
        "totalBytes",
        longValue("SELECT COALESCE(SUM(size_bytes),0) FROM document WHERE kb_id=?", kbId));
    int questionCount = count("SELECT COUNT(*) FROM chat_audit WHERE kb_id=?", kbId);
    result.put("questionCount", questionCount);
    result.put(
        "avgDurationMs",
        longValue("SELECT COALESCE(AVG(duration_ms),0) FROM chat_audit WHERE kb_id=?", kbId));
    result.put(
        "readyRate",
        total == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(ready * 100.0 / total).setScale(1, RoundingMode.HALF_UP));
    result.put("statusDistribution", statusDistribution(kbId));
    result.put("formatDistribution", formatDistribution(kbId));
    result.put("recentConversations", recentConversations(userId, kbId));
    return result;
  }

  private List<Map<String, Object>> statusDistribution(Long kbId) {
    return jdbc.query(
        "SELECT status,COUNT(*) total FROM document WHERE kb_id=? GROUP BY status ORDER BY total DESC",
        new Object[] {kbId},
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("status", r.getString("status"));
          item.put("count", r.getInt("total"));
          return item;
        });
  }

  private List<Map<String, Object>> formatDistribution(Long kbId) {
    return jdbc.query(
        "SELECT UPPER(SUBSTRING_INDEX(original_name,'.',-1)) file_format,COUNT(*) total "
            + "FROM document WHERE kb_id=? "
            + "GROUP BY UPPER(SUBSTRING_INDEX(original_name,'.',-1)) ORDER BY total DESC LIMIT 6",
        new Object[] {kbId},
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("format", r.getString("file_format"));
          item.put("count", r.getInt("total"));
          return item;
        });
  }

  private List<Map<String, Object>> recentQuestions(Long userId, String role, Long kbId) {
    String sql =
        "SELECT a.id,u.display_name,a.question,a.source_count,a.duration_ms,a.created_at "
            + "FROM chat_audit a JOIN app_user u ON u.id=a.user_id WHERE a.kb_id=? "
            + ("ADMIN".equals(role) ? "" : "AND a.user_id=? ")
            + "ORDER BY a.id DESC LIMIT 12";
    Object[] args = "ADMIN".equals(role) ? new Object[] {kbId} : new Object[] {kbId, userId};
    return jdbc.query(
        sql,
        args,
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("id", r.getLong("id"));
          item.put("userName", r.getString("display_name"));
          item.put("question", r.getString("question"));
          item.put("sourceCount", r.getInt("source_count"));
          item.put("durationMs", r.getLong("duration_ms"));
          item.put("createdAt", r.getTimestamp("created_at").toLocalDateTime());
          return item;
        });
  }

  private List<Map<String, Object>> recentConversations(Long userId, Long kbId) {
    return jdbc.query(
        "SELECT c.id,c.title,c.updated_at,COUNT(m.id) message_count,"
            + "COALESCE((SELECT LEFT(m2.content,100) FROM chat_message m2 WHERE m2.conversation_id=c.id ORDER BY m2.id DESC LIMIT 1),'') preview "
            + "FROM chat_conversation c LEFT JOIN chat_message m ON m.conversation_id=c.id "
            + "WHERE c.kb_id=? AND c.user_id=? GROUP BY c.id ORDER BY c.updated_at DESC,c.id DESC LIMIT 12",
        new Object[] {kbId, userId},
        (result, row) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("id", result.getLong("id"));
          item.put("title", result.getString("title"));
          item.put("messageCount", result.getInt("message_count"));
          item.put("preview", result.getString("preview"));
          item.put("updatedAt", result.getTimestamp("updated_at").toLocalDateTime());
          return item;
        });
  }

  private int count(String sql, Long kbId) {
    Integer value = jdbc.queryForObject(sql, new Object[] {kbId}, Integer.class);
    return value == null ? 0 : value;
  }

  private long longValue(String sql, Long kbId) {
    Number value = jdbc.queryForObject(sql, new Object[] {kbId}, Number.class);
    return value == null ? 0 : value.longValue();
  }
}
