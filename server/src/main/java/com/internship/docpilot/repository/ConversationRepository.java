package com.internship.docpilot.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
  private final JdbcTemplate jdbc;

  public ConversationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Long create(Long kbId, Long userId, String title) {
    KeyHolder holder = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          PreparedStatement statement = connection.prepareStatement(
              "INSERT INTO chat_conversation(kb_id,user_id,title) VALUES(?,?,?)",
              Statement.RETURN_GENERATED_KEYS);
          statement.setLong(1, kbId);
          statement.setLong(2, userId);
          statement.setString(3, title);
          return statement;
        }, holder);
    return holder.getKey().longValue();
  }

  public List<Map<String, Object>> list(Long kbId, Long userId) {
    return jdbc.query(
        "SELECT c.id,c.title,c.created_at,c.updated_at,COUNT(m.id) message_count,"
            + "COALESCE((SELECT LEFT(m2.content,120) FROM chat_message m2 WHERE m2.conversation_id=c.id ORDER BY m2.id DESC LIMIT 1),'') preview "
            + "FROM chat_conversation c LEFT JOIN chat_message m ON m.conversation_id=c.id "
            + "WHERE c.kb_id=? AND c.user_id=? GROUP BY c.id ORDER BY c.updated_at DESC,c.id DESC",
        new Object[] {kbId, userId},
        (result, row) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("id", result.getLong("id"));
          item.put("title", result.getString("title"));
          item.put("messageCount", result.getInt("message_count"));
          item.put("preview", result.getString("preview"));
          item.put("createdAt", result.getTimestamp("created_at").toLocalDateTime());
          item.put("updatedAt", result.getTimestamp("updated_at").toLocalDateTime());
          return item;
        });
  }

  public Map<String, Object> owned(Long id, Long userId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id,kb_id,user_id,title FROM chat_conversation WHERE id=? AND user_id=?",
        id, userId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public List<Map<String, Object>> messages(Long conversationId) {
    return jdbc.query(
        "SELECT id,role,content,sources_json,created_at FROM chat_message WHERE conversation_id=? ORDER BY id",
        new Object[] {conversationId},
        (result, row) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("id", result.getLong("id"));
          item.put("role", result.getString("role"));
          item.put("content", result.getString("content"));
          item.put("sourcesJson", result.getString("sources_json"));
          item.put("createdAt", result.getTimestamp("created_at").toLocalDateTime());
          return item;
        });
  }

  public int messageCount(Long conversationId) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM chat_message WHERE conversation_id=?",
        new Object[] {conversationId}, Integer.class);
    return count == null ? 0 : count;
  }

  public void addMessage(Long conversationId, String role, String content, String sourcesJson) {
    jdbc.update(
        "INSERT INTO chat_message(conversation_id,role,content,sources_json) VALUES(?,?,?,?)",
        conversationId, role, content, sourcesJson);
    jdbc.update("UPDATE chat_conversation SET updated_at=NOW() WHERE id=?", conversationId);
  }

  public void rename(Long id, Long userId, String title) {
    jdbc.update("UPDATE chat_conversation SET title=?,updated_at=NOW() WHERE id=? AND user_id=?", title, id, userId);
  }

  public void delete(Long id, Long userId) {
    jdbc.update(
        "DELETE m FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE c.id=? AND c.user_id=?",
        id, userId);
    jdbc.update("DELETE FROM chat_conversation WHERE id=? AND user_id=?", id, userId);
  }
}
