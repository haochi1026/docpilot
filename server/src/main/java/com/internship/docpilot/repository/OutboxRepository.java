package com.internship.docpilot.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
  private final JdbcTemplate jdbc;

  public OutboxRepository(JdbcTemplate j) {
    jdbc = j;
  }

  public void create(Long documentId) {
    String event = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO outbox_message(event_id,aggregate_id,event_type,payload,status) VALUES(?,?,'DOCUMENT_PARSE',?,'PENDING')",
        event,
        documentId,
        "{\"documentId\":" + documentId + "}");
  }

  public Map<String, Object> next() {
    List<Map<String, Object>> x =
        jdbc.queryForList(
            "SELECT id,aggregate_id,retry_count FROM outbox_message WHERE status IN('PENDING','RETRY') AND next_retry_at<=NOW() ORDER BY id LIMIT 1");
    return x.isEmpty() ? null : x.get(0);
  }

  public boolean claim(Long id) {
    return jdbc.update(
            "UPDATE outbox_message SET status='SENDING' WHERE id=? AND status IN('PENDING','RETRY')",
            id)
        == 1;
  }

  public void sent(Long id) {
    jdbc.update("UPDATE outbox_message SET status='SENT' WHERE id=?", id);
  }

  public void retry(Long id, int count) {
    jdbc.update(
        "UPDATE outbox_message SET status=IF(? >= 5,'DEAD','RETRY'),retry_count=?,next_retry_at=DATE_ADD(NOW(),INTERVAL ? SECOND) WHERE id=?",
        count,
        count,
        Math.min(60, 1 << Math.min(count, 5)),
        id);
  }
}
