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
        "INSERT IGNORE INTO outbox_message(event_id,aggregate_id,event_type,payload,status) "
            + "VALUES(?,?,'DOCUMENT_PARSE',?,'PENDING')",
        event,
        documentId,
        "{\"documentId\":" + documentId + "}");
  }

  public void createDelete(Long documentId) {
    String event = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT IGNORE INTO outbox_message(event_id,aggregate_id,event_type,payload,status) "
            + "VALUES(?,?,'DOCUMENT_DELETE',?,'PENDING')",
        event,
        documentId,
        "{\"documentId\":" + documentId + "}");
  }

  public Map<String, Object> next() {
    List<Map<String, Object>> x = nextBatch(1);
    return x.isEmpty() ? null : x.get(0);
  }

  public List<Map<String, Object>> nextBatch(int limit) {
    return jdbc.queryForList(
        "SELECT id,aggregate_id,event_type,retry_count FROM outbox_message WHERE status IN('PENDING','RETRY') "
            + "AND retry_count<5 AND next_retry_at<=NOW() ORDER BY id LIMIT "
            + Math.max(1, Math.min(limit, 200)));
  }

  public boolean claim(Long id, String owner) {
    return jdbc.update(
        "UPDATE outbox_message SET status='SENDING',claimed_at=NOW(),claimed_by=? "
                + "WHERE id=? AND status IN('PENDING','RETRY') AND retry_count<5",
            owner,
            id)
        == 1;
  }

  public void sent(Long id) {
    jdbc.update(
        "UPDATE outbox_message SET status='SENT',claimed_at=NULL,claimed_by=NULL,dispatched_at=NOW() WHERE id=?", id);
  }

  public void retry(Long id, int count) {
    retry(id, count, "publish failed");
  }

  public void retry(Long id, int count, String reason) {
    jdbc.update(
        "UPDATE outbox_message SET status=IF(? >= 5,'DEAD','RETRY'),retry_count=?,"
            + "dead_letter_reason=IF(? >= 5,?,NULL),claimed_at=NULL,claimed_by=NULL,next_retry_at=DATE_ADD(NOW(),INTERVAL ? SECOND) "
            + "WHERE id=?",
        count,
        count,
        count,
        reason == null ? "publish failed" : reason.substring(0, Math.min(500, reason.length())),
        Math.min(60, 1 << Math.min(count, 5)),
        id);
  }

  public int recoverStaleSending(int leaseMinutes) {
    return jdbc.update(
        "UPDATE outbox_message SET status=IF(retry_count+1>=5,'DEAD','RETRY'),"
            + "retry_count=retry_count+1,dead_letter_reason=IF(retry_count+1>=5,'dispatcher lease expired',NULL),"
            + "claimed_at=NULL,claimed_by=NULL,next_retry_at=NOW() "
            + "WHERE status='SENDING' AND claimed_at < DATE_SUB(NOW(), INTERVAL ? MINUTE)",
        leaseMinutes);
  }

  public int pendingCount() {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_message WHERE status IN('PENDING','RETRY','SENDING')",
        Integer.class);
    return count == null ? 0 : count;
  }

  public int deadCount() {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_message WHERE status='DEAD'", Integer.class);
    return count == null ? 0 : count;
  }

  public long oldestOpenAgeSeconds() {
    Long age = jdbc.queryForObject(
        "SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,NOW())),0) "
            + "FROM outbox_message WHERE status IN('PENDING','RETRY','SENDING')",
        Long.class);
    return age == null ? 0 : Math.max(0, age);
  }

  public int replayDead(Long id) {
    return jdbc.update(
        "UPDATE outbox_message SET status='RETRY',retry_count=0,dead_letter_reason=NULL,next_retry_at=NOW(),claimed_at=NULL,claimed_by=NULL WHERE id=? AND status='DEAD'",
        id);
  }

  public boolean hasOpenForDocument(Long documentId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_message "
                + "WHERE aggregate_id=? AND event_type='DOCUMENT_PARSE' "
                + "AND status IN('PENDING','RETRY','SENDING')",
            new Object[] {documentId},
            Integer.class);
    return count != null && count > 0;
  }
}
