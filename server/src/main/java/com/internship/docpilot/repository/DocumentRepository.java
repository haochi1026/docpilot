package com.internship.docpilot.repository;

import com.internship.docpilot.model.DocumentView;
import com.internship.docpilot.model.SearchHit;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentRepository {
  private final JdbcTemplate jdbc;

  public DocumentRepository(JdbcTemplate j) {
    jdbc = j;
  }

  public Long insert(Long kbId, Long owner, String name, String key, String type, long size) {
    KeyHolder h = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          PreparedStatement p =
              c.prepareStatement(
                  "INSERT INTO document(kb_id,owner_id,original_name,object_key,content_type,size_bytes,status) VALUES(?,?,?,?,?,?,'PENDING')",
                  Statement.RETURN_GENERATED_KEYS);
          p.setLong(1, kbId);
          p.setLong(2, owner);
          p.setString(3, name);
          p.setString(4, key);
          p.setString(5, type);
          p.setLong(6, size);
          return p;
        },
        h);
    return h.getKey().longValue();
  }

  public List<DocumentView> byKb(Long kbId) {
    return jdbc.query(
        "SELECT id,kb_id,original_name,content_type,size_bytes,status,error_message,created_at FROM document WHERE kb_id=? ORDER BY id DESC",
        new Object[] {kbId},
        (r, i) -> map(r));
  }

  public Map<String, Object> details(Long id) {
    List<Map<String, Object>> x =
        jdbc.queryForList(
            "SELECT id,kb_id,original_name,object_key,content_type,status FROM document WHERE id=?",
            id);
    return x.isEmpty() ? null : x.get(0);
  }

  public Long kbId(Long id) {
    List<Long> rows =
        jdbc.query(
            "SELECT kb_id FROM document WHERE id=?",
            new Object[] {id},
            (r, i) -> r.getLong("kb_id"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public String objectKey(Long id) {
    List<String> rows =
        jdbc.query(
            "SELECT object_key FROM document WHERE id=?",
            new Object[] {id},
            (r, i) -> r.getString("object_key"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> detailView(Long id) {
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT d.id,d.kb_id,d.original_name,d.content_type,d.size_bytes,d.status,"
                + "d.error_message,d.version,d.created_at,d.updated_at,COUNT(c.id) chunk_count "
                + "FROM document d LEFT JOIN document_chunk c ON c.document_id=d.id "
                + "WHERE d.id=? GROUP BY d.id",
            new Object[] {id},
            (r, i) -> {
              Map<String, Object> item = new LinkedHashMap<String, Object>();
              item.put("id", r.getLong("id"));
              item.put("kbId", r.getLong("kb_id"));
              item.put("originalName", r.getString("original_name"));
              item.put("contentType", r.getString("content_type"));
              item.put("sizeBytes", r.getLong("size_bytes"));
              item.put("status", r.getString("status"));
              item.put("errorMessage", r.getString("error_message"));
              item.put("version", r.getInt("version"));
              item.put("chunkCount", r.getInt("chunk_count"));
              item.put("createdAt", r.getTimestamp("created_at").toLocalDateTime());
              item.put("updatedAt", r.getTimestamp("updated_at").toLocalDateTime());
              return item;
            });
    return rows.isEmpty() ? null : rows.get(0);
  }

  public List<Map<String, Object>> chunkPreview(Long id) {
    return jdbc.query(
        "SELECT chunk_no,page_no,content FROM document_chunk WHERE document_id=? ORDER BY chunk_no LIMIT 12",
        new Object[] {id},
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("chunkNo", r.getInt("chunk_no"));
          item.put("pageNo", r.getObject("page_no"));
          item.put("content", r.getString("content"));
          return item;
        });
  }

  public int retry(Long id) {
    return jdbc.update(
        "UPDATE document SET status='PENDING',error_message=NULL WHERE id=? AND status='FAILED'", id);
  }

  public boolean claim(Long id) {
    return jdbc.update(
            "UPDATE document SET status='PROCESSING',error_message=NULL WHERE id=? AND status IN('PENDING','FAILED')",
            id)
        == 1;
  }

  public void success(Long id) {
    jdbc.update("UPDATE document SET status='SUCCESS',error_message=NULL WHERE id=?", id);
  }

  public void failed(Long id, String m) {
    jdbc.update(
        "UPDATE document SET status='FAILED',error_message=? WHERE id=?",
        m.length() > 480 ? m.substring(0, 480) : m,
        id);
  }

  @Transactional
  public void replaceChunks(Long id, List<String> chunks) {
    jdbc.update("DELETE FROM document_chunk WHERE document_id=?", id);
    List<Object[]> args = new ArrayList<Object[]>();
    for (int i = 0; i < chunks.size(); i++) args.add(new Object[] {id, i, chunks.get(i)});
    jdbc.batchUpdate(
        "INSERT INTO document_chunk(document_id,chunk_no,content) VALUES(?,?,?)", args);
  }

  public List<SearchHit> candidates(Long kbId, String embeddingModel) {
    return jdbc.query(
        "SELECT c.id chunk_id,c.document_id,d.original_name,c.page_no,c.content,e.vector_json "
            + "FROM document_chunk c JOIN document d ON d.id=c.document_id "
            + "LEFT JOIN chunk_embedding e ON e.chunk_id=c.id AND e.model_name=? "
            + "WHERE d.kb_id=? AND d.status='SUCCESS' ORDER BY c.id LIMIT 500",
        new Object[] {embeddingModel, kbId},
        (r, i) ->
            new SearchHit(
                r.getLong("chunk_id"),
                r.getLong("document_id"),
                r.getString("original_name"),
                r.getString("content"),
                (Integer) r.getObject("page_no"),
                0,
                r.getString("vector_json")));
  }

  public Map<String, Object> agentChunk(Long chunkId) {
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT d.kb_id,c.id chunk_id,c.document_id,d.original_name,c.page_no,c.content "
                + "FROM document_chunk c JOIN document d ON d.id=c.document_id "
                + "WHERE c.id=? AND d.status='SUCCESS'",
            new Object[] {chunkId},
            (r, i) -> {
              Map<String, Object> item = new LinkedHashMap<String, Object>();
              item.put("kbId", r.getLong("kb_id"));
              item.put("chunkId", r.getLong("chunk_id"));
              item.put("documentId", r.getLong("document_id"));
              item.put("documentName", r.getString("original_name"));
              item.put("pageNo", r.getObject("page_no"));
              item.put("content", r.getString("content"));
              item.put("score", 1.0d);
              return item;
            });
    return rows.isEmpty() ? null : rows.get(0);
  }

  public List<Map<String, Object>> chunksForDocument(Long documentId) {
    return jdbc.query(
        "SELECT id,content FROM document_chunk WHERE document_id=? ORDER BY chunk_no",
        new Object[] {documentId},
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("id", r.getLong("id"));
          item.put("content", r.getString("content"));
          return item;
        });
  }

  public List<Long> successfulDocumentIds(Long kbId) {
    return jdbc.query(
        "SELECT id FROM document WHERE kb_id=? AND status='SUCCESS' ORDER BY id",
        new Object[] {kbId},
        (r, i) -> r.getLong("id"));
  }

  public void saveEmbedding(Long chunkId, String model, String vectorJson) {
    jdbc.update(
        "INSERT INTO chunk_embedding(chunk_id,model_name,vector_json) VALUES(?,?,?) "
            + "ON DUPLICATE KEY UPDATE vector_json=VALUES(vector_json)",
        chunkId,
        model,
        vectorJson);
  }

  public List<Long> staleProcessingIds(int leaseMinutes) {
    return jdbc.query(
        "SELECT id FROM document WHERE status='PROCESSING' "
            + "AND updated_at < DATE_SUB(NOW(), INTERVAL ? MINUTE) ORDER BY updated_at LIMIT 50",
        new Object[] {leaseMinutes},
        (r, i) -> r.getLong("id"));
  }

  public List<Long> stalePendingIds(int leaseMinutes) {
    return jdbc.query(
        "SELECT id FROM document WHERE status='PENDING' "
            + "AND updated_at < DATE_SUB(NOW(), INTERVAL ? MINUTE) ORDER BY updated_at LIMIT 50",
        new Object[] {leaseMinutes},
        (r, i) -> r.getLong("id"));
  }

  public boolean releaseStaleProcessing(Long id, int leaseMinutes) {
    return jdbc.update(
            "UPDATE document SET status='PENDING',error_message='上次解析任务超时，已恢复为待重试' "
                + "WHERE id=? AND status='PROCESSING' "
                + "AND updated_at < DATE_SUB(NOW(), INTERVAL ? MINUTE)",
            id,
            leaseMinutes)
        == 1;
  }

  public int countChunks(Long kbId) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM document_chunk c JOIN document d ON d.id=c.document_id WHERE d.kb_id=? AND d.status='SUCCESS'",
        new Object[] {kbId}, Integer.class);
    return count == null ? 0 : count;
  }

  public int countEmbeddings(Long kbId, String model) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM chunk_embedding e JOIN document_chunk c ON c.id=e.chunk_id JOIN document d ON d.id=c.document_id WHERE d.kb_id=? AND d.status='SUCCESS' AND e.model_name=?",
        new Object[] {kbId, model}, Integer.class);
    return count == null ? 0 : count;
  }

  public void delete(Long id) {
    jdbc.update(
        "DELETE e FROM chunk_embedding e JOIN document_chunk c ON c.id=e.chunk_id WHERE c.document_id=?",
        id);
    jdbc.update("DELETE FROM document_chunk WHERE document_id=?", id);
    jdbc.update("DELETE FROM outbox_message WHERE aggregate_id=?", id);
    jdbc.update("DELETE FROM document WHERE id=?", id);
  }

  private DocumentView map(java.sql.ResultSet r) throws java.sql.SQLException {
    DocumentView d = new DocumentView();
    d.setId(r.getLong("id"));
    d.setKbId(r.getLong("kb_id"));
    d.setOriginalName(r.getString("original_name"));
    d.setContentType(r.getString("content_type"));
    d.setSizeBytes(r.getLong("size_bytes"));
    d.setStatus(r.getString("status"));
    d.setErrorMessage(r.getString("error_message"));
    d.setCreatedAt(r.getTimestamp("created_at").toLocalDateTime());
    return d;
  }
}
