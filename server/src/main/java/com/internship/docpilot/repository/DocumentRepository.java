package com.internship.docpilot.repository;

import com.internship.docpilot.model.DocumentView;
import com.internship.docpilot.model.DocumentChunkDraft;
import com.internship.docpilot.model.RetrievalCandidate;
import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.service.TokenEstimator;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentRepository {
  private final JdbcTemplate jdbc;
  private final TokenEstimator tokens;

  public DocumentRepository(JdbcTemplate j, TokenEstimator tokens) {
    jdbc = j;
    this.tokens = tokens;
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
        "SELECT id,kb_id,original_name,content_type,size_bytes,status,error_message,embedding_status,embedding_error,created_at FROM document WHERE kb_id=? AND status<>'DELETED' ORDER BY id DESC",
        new Object[] {kbId},
        (r, i) -> map(r));
  }

  public Map<String, Object> details(Long id) {
    List<Map<String, Object>> x =
        jdbc.queryForList(
            "SELECT id,kb_id,original_name,object_key,content_type,status,parse_job_id,version,active_revision FROM document WHERE id=? AND status<>'DELETED'",
            id);
    return x.isEmpty() ? null : x.get(0);
  }

  public Long kbId(Long id) {
    List<Long> rows =
        jdbc.query(
            "SELECT kb_id FROM document WHERE id=? AND status<>'DELETED'",
            new Object[] {id},
            (r, i) -> r.getLong("kb_id"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public String objectKey(Long id) {
    List<String> rows =
        jdbc.query(
            "SELECT object_key FROM document WHERE id=? AND status<>'DELETED'",
            new Object[] {id},
            (r, i) -> r.getString("object_key"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public String objectKeyAny(Long id) {
    List<String> rows = jdbc.query(
        "SELECT object_key FROM document WHERE id=?",
        new Object[] {id},
        (r, i) -> r.getString("object_key"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> detailView(Long id) {
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT d.id,d.kb_id,d.original_name,d.content_type,d.size_bytes,d.status,"
                + "d.error_message,d.embedding_status,d.embedding_error,d.extraction_method,d.page_count,"
                + "d.embedding_model,d.version,d.active_revision,d.created_at,d.updated_at,COUNT(c.id) chunk_count "
                + "FROM document d LEFT JOIN document_chunk c ON c.document_id=d.id AND c.revision_no=d.active_revision "
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
              item.put("embeddingStatus", r.getString("embedding_status"));
              item.put("embeddingError", r.getString("embedding_error"));
              item.put("extractionMethod", r.getString("extraction_method"));
              item.put("pageCount", r.getObject("page_count"));
              item.put("embeddingModel", r.getString("embedding_model"));
              item.put("version", r.getInt("version"));
              item.put("activeRevision", r.getInt("active_revision"));
              item.put("chunkCount", r.getInt("chunk_count"));
              item.put("createdAt", r.getTimestamp("created_at").toLocalDateTime());
              item.put("updatedAt", r.getTimestamp("updated_at").toLocalDateTime());
              return item;
            });
    return rows.isEmpty() ? null : rows.get(0);
  }

  public List<Map<String, Object>> chunkPreview(Long id) {
    return jdbc.query(
        "SELECT c.chunk_no,c.page_no,c.heading,c.token_count,c.content FROM document_chunk c "
            + "JOIN document d ON d.id=c.document_id AND d.active_revision=c.revision_no "
            + "WHERE c.document_id=? ORDER BY c.chunk_no LIMIT 12",
        new Object[] {id},
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("chunkNo", r.getInt("chunk_no"));
          item.put("pageNo", r.getObject("page_no"));
          item.put("heading", r.getString("heading"));
          item.put("tokenCount", r.getInt("token_count"));
          item.put("content", r.getString("content"));
          return item;
        });
  }

  public int retry(Long id) {
    return jdbc.update(
        "UPDATE document SET status='PENDING',parse_job_id=NULL,lease_until=NULL,heartbeat_at=NULL,error_message=NULL,embedding_status='PENDING',embedding_error=NULL "
            + "WHERE id=? AND status IN('FAILED','PARTIAL')",
        id);
  }

  public boolean claim(Long id) {
    return claim(id, 30);
  }

  @Transactional
  public boolean claim(Long id, int leaseMinutes) {
    String jobId = UUID.randomUUID().toString();
    boolean claimed = jdbc.update(
            "UPDATE document SET status='PROCESSING',version=version+1,parse_job_id=?,lease_until=DATE_ADD(NOW(),INTERVAL ? MINUTE),heartbeat_at=NOW(),error_message=NULL WHERE id=? AND status IN('PENDING','FAILED','PARTIAL')",
            jobId, leaseMinutes,
            id)
        == 1;
    if (claimed) {
      jdbc.update(
          "INSERT INTO document_revision(document_id,revision_no,parse_job_id,status) "
              + "SELECT id,version,?,'PROCESSING' FROM document WHERE id=?",
          jobId,
          id);
    }
    return claimed;
  }

  public boolean touchLease(Long id, String jobId, int leaseMinutes) {
    return jdbc.update(
        "UPDATE document SET lease_until=DATE_ADD(NOW(),INTERVAL ? MINUTE),heartbeat_at=NOW() WHERE id=? AND status='PROCESSING' AND parse_job_id=?",
        leaseMinutes, id, jobId) == 1;
  }

  public void success(Long id, String embeddingStatus, String extractionMethod, int pageCount) {
    success(id, null, embeddingStatus, extractionMethod, pageCount);
  }

  @Transactional
  public void success(Long id, String jobId, String embeddingStatus, String extractionMethod, int pageCount) {
    jdbc.update(
        "UPDATE document_revision r JOIN document d ON d.id=r.document_id "
            + "SET r.status='PUBLISHED',r.extraction_method=?,r.page_count=?,"
            + "r.embedding_status=?,r.embedding_model=d.embedding_model,"
            + "r.error_message=NULL,r.published_at=NOW() "
            + "WHERE r.document_id=? AND r.parse_job_id=? AND r.status='PROCESSING' "
            + "AND d.status='PROCESSING' AND d.parse_job_id=?",
        extractionMethod, pageCount, embeddingStatus, id, jobId, jobId);
    jdbc.update(
        "UPDATE document SET status='SUCCESS',parse_job_id=NULL,lease_until=NULL,heartbeat_at=NULL,error_message=NULL,embedding_status=?,embedding_error=NULL,"
            + "extraction_method=?,page_count=?,active_revision=version WHERE id=? AND status='PROCESSING' AND (? IS NULL OR parse_job_id=?)",
        embeddingStatus,
        extractionMethod,
        pageCount,
        id,
        jobId,
        jobId);
  }

  public void partial(
      Long id, String embeddingError, String extractionMethod, int pageCount) {
    partial(id, null, embeddingError, extractionMethod, pageCount);
  }

  @Transactional
  public void partial(Long id, String jobId, String embeddingError, String extractionMethod, int pageCount) {
    String message = safe(embeddingError);
    jdbc.update(
        "UPDATE document_revision r JOIN document d ON d.id=r.document_id "
            + "SET r.status='PUBLISHED',r.extraction_method=?,r.page_count=?,"
            + "r.embedding_status='FAILED',r.error_message=?,r.published_at=NOW() "
            + "WHERE r.document_id=? AND r.parse_job_id=? AND r.status='PROCESSING' "
            + "AND d.status='PROCESSING' AND d.parse_job_id=?",
        extractionMethod, pageCount, message, id, jobId, jobId);
    jdbc.update(
        "UPDATE document SET status='PARTIAL',parse_job_id=NULL,lease_until=NULL,heartbeat_at=NULL,error_message=?,embedding_status='FAILED',embedding_error=?,"
            + "extraction_method=?,page_count=?,active_revision=version WHERE id=? AND status='PROCESSING' AND (? IS NULL OR parse_job_id=?)",
        "文本解析成功，但向量索引失败：" + message,
        message,
        extractionMethod,
        pageCount,
        id,
        jobId,
        jobId);
  }

  public void failed(Long id, String m) {
    failed(id, null, m);
  }

  @Transactional
  public void failed(Long id, String jobId, String m) {
    jdbc.update(
        "UPDATE document_revision SET status='FAILED',error_message=? "
            + "WHERE document_id=? AND parse_job_id=? AND status='PROCESSING'",
        safe(m), id, jobId);
    jdbc.update(
        "UPDATE document SET status=CASE WHEN active_revision>0 THEN 'PARTIAL' ELSE 'FAILED' END,"
            + "parse_job_id=NULL,lease_until=NULL,heartbeat_at=NULL,error_message=?,"
            + "embedding_status=CASE WHEN active_revision>0 THEN embedding_status ELSE 'NOT_INDEXED' END,"
            + "embedding_error=CASE WHEN active_revision>0 THEN embedding_error ELSE NULL END "
            + "WHERE id=? AND status='PROCESSING' AND (? IS NULL OR parse_job_id=?)",
        safe(m),
        id,
        jobId,
        jobId);
  }

  @Transactional
  public void replaceChunks(Long id, Long kbId, List<DocumentChunkDraft> chunks) {
    replaceChunks(id, kbId, chunks, null);
  }

  @Transactional
  public void replaceChunks(Long id, Long kbId, List<DocumentChunkDraft> chunks, String jobId) {
    if (jobId != null && !touchLease(id, jobId, 30)) {
      throw new IllegalStateException("document parse lease lost");
    }
    Integer revision = jdbc.queryForObject(
        "SELECT version FROM document WHERE id=? AND (? IS NULL OR parse_job_id=?)",
        new Object[] {id, jobId, jobId},
        Integer.class);
    if (revision == null) throw new IllegalStateException("document revision is unavailable");
    jdbc.update(
        "DELETE t FROM document_chunk_term t JOIN document_chunk c ON c.id=t.chunk_id "
            + "WHERE c.document_id=? AND c.revision_no=?",
        id,
        revision);
    jdbc.update(
        "DELETE FROM document_chunk WHERE document_id=? AND revision_no=?", id, revision);
    List<Object[]> termRows = new ArrayList<Object[]>();
    List<Object[]> indexedRows = new ArrayList<Object[]>();
    for (int i = 0; i < chunks.size(); i++) {
      if (jobId != null && i > 0 && i % 8 == 0 && !touchLease(id, jobId, 30)) {
        throw new IllegalStateException("document parse lease lost while writing chunks");
      }
      DocumentChunkDraft chunk = chunks.get(i);
      KeyHolder holder = new GeneratedKeyHolder();
      final int chunkNo = i;
      jdbc.update(
          connection -> {
            PreparedStatement statement =
                connection.prepareStatement(
                    "INSERT INTO document_chunk(document_id,revision_no,chunk_no,page_no,heading,token_count,content) VALUES(?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, id);
            statement.setInt(2, revision);
            statement.setInt(3, chunkNo);
            if (chunk.getPageNo() == null) statement.setNull(4, java.sql.Types.INTEGER);
            else statement.setInt(4, chunk.getPageNo());
            statement.setString(5, chunk.getHeading());
            statement.setInt(6, chunk.getTokenCount());
            statement.setString(7, chunk.getContent());
            return statement;
          },
          holder);
      Long chunkId = holder.getKey().longValue();
      indexedRows.add(new Object[] {chunkId});
      for (Map.Entry<String, Integer> term :
          tokens.termFrequencies(chunk.getContent()).entrySet()) {
        termRows.add(new Object[] {chunkId, kbId, term.getKey(), term.getValue()});
      }
    }
    if (!termRows.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO document_chunk_term(chunk_id,kb_id,term_value,term_frequency) VALUES(?,?,?,?)",
          termRows);
    }
    jdbc.batchUpdate("UPDATE document_chunk SET lexical_indexed=1 WHERE id=?", indexedRows);
  }

  @Transactional
  public int backfillMissingLexicalTerms(int limit) {
    List<Map<String, Object>> chunks =
        jdbc.queryForList(
            "SELECT c.id,d.kb_id,c.content FROM document_chunk c JOIN document d ON d.id=c.document_id "
                + "WHERE c.revision_no=d.active_revision AND (c.lexical_indexed=0 OR c.token_count=0) "
                + "ORDER BY c.id LIMIT ?",
            Math.max(1, Math.min(limit, 1000)));
    for (Map<String, Object> chunk : chunks) {
      Long chunkId = ((Number) chunk.get("id")).longValue();
      Long kbId = ((Number) chunk.get("kb_id")).longValue();
      Map<String, Integer> terms = tokens.termFrequencies(String.valueOf(chunk.get("content")));
      List<Object[]> rows = new ArrayList<Object[]>();
      for (Map.Entry<String, Integer> term : terms.entrySet()) {
        rows.add(new Object[] {chunkId, kbId, term.getKey(), term.getValue()});
      }
      if (!rows.isEmpty()) {
        jdbc.batchUpdate(
            "INSERT IGNORE INTO document_chunk_term(chunk_id,kb_id,term_value,term_frequency) VALUES(?,?,?,?)",
            rows);
      }
      jdbc.update(
          "UPDATE document_chunk SET token_count=?,lexical_indexed=1 WHERE id=?",
          tokens.estimate(String.valueOf(chunk.get("content"))),
          chunkId);
    }
    return chunks.size();
  }

  public List<RetrievalCandidate> lexicalCandidates(
      Long kbId, List<String> queryTerms, int candidateLimit) {
    if (queryTerms == null || queryTerms.isEmpty()) return Collections.emptyList();
    String placeholders = placeholders(queryTerms.size());
    List<Object> statsArgs = new ArrayList<Object>();
    statsArgs.add(kbId);
    Map<String, Object> stats =
        jdbc.queryForMap(
            "SELECT COUNT(*) chunk_count,COALESCE(AVG(c.token_count),1) avg_length "
                + "FROM document_chunk c JOIN document d ON d.id=c.document_id "
                + "WHERE d.kb_id=? AND c.revision_no=d.active_revision AND c.lexical_indexed=1 "
                + "AND d.status<>'DELETED' AND d.active_revision>0",
            statsArgs.toArray());
    int documentCount = ((Number) stats.get("chunk_count")).intValue();
    double avgLength = ((Number) stats.get("avg_length")).doubleValue();
    if (documentCount == 0) return Collections.emptyList();

    List<Object> arguments = new ArrayList<Object>();
    arguments.add(kbId);
    arguments.addAll(queryTerms);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT t.chunk_id,t.term_value,t.term_frequency,c.token_count,df.document_frequency "
                + "FROM document_chunk_term t JOIN document_chunk c ON c.id=t.chunk_id "
                + "JOIN document d ON d.id=c.document_id "
                + "JOIN (SELECT t2.term_value,COUNT(*) document_frequency "
                + "FROM document_chunk_term t2 JOIN document_chunk c2 ON c2.id=t2.chunk_id "
                + "JOIN document d2 ON d2.id=c2.document_id "
                + "WHERE t2.kb_id=? AND c2.revision_no=d2.active_revision "
                + "AND d2.status<>'DELETED' AND t2.term_value IN ("
                + placeholders
                + ") GROUP BY t2.term_value) df ON df.term_value=t.term_value "
                + "WHERE t.kb_id=? AND t.term_value IN ("
                + placeholders
                + ") AND c.revision_no=d.active_revision AND c.lexical_indexed=1 "
                + "AND d.status<>'DELETED' AND d.active_revision>0",
            lexicalArguments(kbId, queryTerms));

    Map<Long, Double> scores = new HashMap<Long, Double>();
    for (Map<String, Object> row : rows) {
      Long chunkId = ((Number) row.get("chunk_id")).longValue();
      int tf = ((Number) row.get("term_frequency")).intValue();
      int length = Math.max(1, ((Number) row.get("token_count")).intValue());
      int df = ((Number) row.get("document_frequency")).intValue();
      double idf = Math.log(1 + (documentCount - df + 0.5) / (df + 0.5));
      double denominator = tf + 1.5 * (1 - 0.75 + 0.75 * length / Math.max(1.0, avgLength));
      double value = idf * (tf * 2.5) / denominator;
      scores.put(chunkId, scores.getOrDefault(chunkId, 0.0) + value);
    }
    List<RetrievalCandidate> result = new ArrayList<RetrievalCandidate>();
    for (Map.Entry<Long, Double> score : scores.entrySet()) {
      result.add(new RetrievalCandidate(score.getKey(), score.getValue()));
    }
    result.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
    return result.size() <= candidateLimit
        ? result
        : new ArrayList<RetrievalCandidate>(result.subList(0, candidateLimit));
  }

  public Map<Long, SearchHit> chunksByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) return Collections.emptyMap();
    List<SearchHit> hits =
        jdbc.query(
            "SELECT c.id chunk_id,c.document_id,d.original_name,c.page_no,c.revision_no,c.heading,c.content "
                + "FROM document_chunk c JOIN document d ON d.id=c.document_id WHERE c.id IN ("
                + placeholders(ids.size())
                + ") AND c.revision_no=d.active_revision AND d.status<>'DELETED' "
                + "AND d.active_revision>0",
            ids.toArray(),
            (r, i) -> {
              SearchHit hit =
                  new SearchHit(
                      r.getLong("chunk_id"),
                      r.getLong("document_id"),
                      r.getString("original_name"),
                      r.getString("content"),
                      (Integer) r.getObject("page_no"),
                      r.getInt("revision_no"),
                      0,
                      null);
              hit.setHeading(r.getString("heading"));
              return hit;
            });
    Map<Long, SearchHit> result = new LinkedHashMap<Long, SearchHit>();
    for (SearchHit hit : hits) result.put(hit.getChunkId(), hit);
    return result;
  }

  public Map<String, Object> agentChunk(Long chunkId) {
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT d.kb_id,c.id chunk_id,c.document_id,d.original_name,c.page_no,c.revision_no,c.content "
                + "FROM document_chunk c JOIN document d ON d.id=c.document_id "
                + "WHERE c.id=? AND c.revision_no=d.active_revision "
                + "AND d.status<>'DELETED' AND d.active_revision>0",
            new Object[] {chunkId},
            (r, i) -> {
              Map<String, Object> item = new LinkedHashMap<String, Object>();
              item.put("kbId", r.getLong("kb_id"));
              item.put("chunkId", r.getLong("chunk_id"));
              item.put("documentId", r.getLong("document_id"));
              item.put("documentName", r.getString("original_name"));
              item.put("pageNo", r.getObject("page_no"));
              item.put("revisionNo", r.getInt("revision_no"));
              item.put("content", r.getString("content"));
              item.put("score", 1.0d);
              return item;
            });
    return rows.isEmpty() ? null : rows.get(0);
  }

  public List<Map<String, Object>> chunksForDocument(Long documentId) {
    return jdbc.query(
        "SELECT c.id,c.content FROM document_chunk c JOIN document d ON d.id=c.document_id "
            + "WHERE c.document_id=? AND c.revision_no=d.active_revision ORDER BY c.chunk_no",
        new Object[] {documentId},
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("id", r.getLong("id"));
          item.put("content", r.getString("content"));
          return item;
        });
  }

  public List<Map<String, Object>> chunksForDocumentRevision(
      Long documentId, int revisionNo) {
    return jdbc.query(
        "SELECT id,content FROM document_chunk WHERE document_id=? AND revision_no=? ORDER BY chunk_no",
        new Object[] {documentId, revisionNo},
        (r, i) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("id", r.getLong("id"));
          item.put("content", r.getString("content"));
          return item;
        });
  }

  public int processingRevision(Long documentId, String jobId) {
    Integer revision = jdbc.queryForObject(
        "SELECT version FROM document WHERE id=? AND status='PROCESSING' AND parse_job_id=?",
        new Object[] {documentId, jobId},
        Integer.class);
    if (revision == null) throw new IllegalStateException("document parse revision is unavailable");
    return revision;
  }

  public List<Map<String, Object>> revisionHistory(Long documentId) {
    return jdbc.queryForList(
        "SELECT revision_no,status,extraction_method,page_count,embedding_status,"
            + "embedding_model,error_message,created_at,published_at "
            + "FROM document_revision WHERE document_id=? ORDER BY revision_no DESC LIMIT 20",
        documentId);
  }

  public List<Map<String, Object>> historicalRevisionCandidates(
      int retainCount, int limit) {
    return jdbc.queryForList(
        "SELECT r.document_id,r.revision_no FROM document_revision r "
            + "JOIN document d ON d.id=r.document_id "
            + "WHERE r.status<>'PROCESSING' AND r.revision_no<>d.active_revision "
            + "AND (SELECT COUNT(*) FROM document_revision newer "
            + "WHERE newer.document_id=r.document_id "
            + "AND newer.revision_no>r.revision_no)>=? "
            + "ORDER BY r.created_at LIMIT ?",
        Math.max(1, retainCount),
        Math.max(1, Math.min(limit, 200)));
  }

  public List<Long> chunkIdsForRevision(Long documentId, int revisionNo) {
    return jdbc.query(
        "SELECT id FROM document_chunk WHERE document_id=? AND revision_no=?",
        new Object[] {documentId, revisionNo},
        (r, i) -> r.getLong("id"));
  }

  @Transactional
  public boolean deleteHistoricalRevision(Long documentId, int revisionNo) {
    Integer active = jdbc.queryForObject(
        "SELECT active_revision FROM document WHERE id=?", Integer.class, documentId);
    if (active != null && active == revisionNo) return false;
    jdbc.update(
        "DELETE t FROM document_chunk_term t JOIN document_chunk c ON c.id=t.chunk_id "
            + "WHERE c.document_id=? AND c.revision_no=?",
        documentId,
        revisionNo);
    jdbc.update(
        "DELETE FROM document_chunk WHERE document_id=? AND revision_no=?",
        documentId,
        revisionNo);
    return jdbc.update(
        "DELETE r FROM document_revision r JOIN document d ON d.id=r.document_id "
            + "WHERE r.document_id=? AND r.revision_no=? "
            + "AND r.revision_no<>d.active_revision AND r.status<>'PROCESSING'",
        documentId,
        revisionNo) == 1;
  }

  public List<Long> successfulDocumentIds(Long kbId) {
    return jdbc.query(
        "SELECT id FROM document WHERE kb_id=? AND status IN('SUCCESS','PARTIAL') ORDER BY id",
        new Object[] {kbId},
        (r, i) -> r.getLong("id"));
  }

  public List<Long> knowledgeBaseIdsWithActiveChunks() {
    return jdbc.query(
        "SELECT DISTINCT d.kb_id FROM document d JOIN document_chunk c ON c.document_id=d.id "
            + "AND c.revision_no=d.active_revision WHERE d.status<>'DELETED' "
            + "AND d.active_revision>0 ORDER BY d.kb_id",
        (r, i) -> r.getLong("kb_id"));
  }

  public String activeEmbeddingModel(Long kbId, String fallback) {
    List<String> models =
        jdbc.query(
            "SELECT embedding_model FROM document WHERE kb_id=? AND status IN('SUCCESS','PARTIAL') "
                + "AND embedding_status='READY' AND embedding_model IS NOT NULL "
                + "GROUP BY embedding_model ORDER BY COUNT(*) DESC LIMIT 1",
            new Object[] {kbId},
            (r, i) -> r.getString("embedding_model"));
    return models.isEmpty() ? fallback : models.get(0);
  }

  public void activateEmbeddingModel(Long kbId, String model) {
    jdbc.update(
        "UPDATE document SET embedding_model=?,embedding_status='READY',embedding_error=NULL,"
            + "status='SUCCESS',error_message=NULL WHERE kb_id=? AND status IN('SUCCESS','PARTIAL')",
        model,
        kbId);
  }

  public void markKnowledgeBaseReindexRolledBack(Long kbId, String error) {
    jdbc.update(
        "UPDATE document SET embedding_status=CASE WHEN embedding_model IS NULL THEN 'FAILED' ELSE 'READY' END,"
            + "embedding_error=?,status=CASE WHEN embedding_model IS NULL THEN 'PARTIAL' ELSE 'SUCCESS' END,"
            + "error_message=CASE WHEN embedding_model IS NULL THEN ? ELSE NULL END "
            + "WHERE kb_id=? AND embedding_status='REINDEXING'",
        safe("Shadow rebuild failed and active index was preserved: " + error),
        safe("Vector rebuild failed: " + error),
        kbId);
  }

  public void markKnowledgeBaseReindexing(Long kbId) {
    jdbc.update(
        "UPDATE document SET status='PARTIAL',embedding_status='REINDEXING',"
            + "embedding_error=NULL,error_message='Vector reindex is in progress' "
            + "WHERE kb_id=? AND status IN('SUCCESS','PARTIAL')",
        kbId);
  }

  public void embeddingReady(Long documentId) {
    jdbc.update(
        "UPDATE document SET status='SUCCESS',error_message=NULL,embedding_status='READY',"
            + "embedding_error=NULL WHERE id=? AND status='PARTIAL'",
        documentId);
  }

  public void embeddingModel(Long id, String jobId, String model) {
    jdbc.update(
        "UPDATE document SET embedding_model=? WHERE id=? AND (? IS NULL OR (status='PROCESSING' AND parse_job_id=?))",
        model, id, jobId, jobId);
  }

  public List<Long> needsEmbedding(Long kbId, String model) {
    return jdbc.query(
        "SELECT id FROM document WHERE kb_id=? AND status IN('SUCCESS','PARTIAL') AND (embedding_status<>'READY' OR embedding_model IS NULL OR embedding_model<>?) ORDER BY id",
        new Object[] {kbId, model}, (r, i) -> r.getLong("id"));
  }

  public void markKnowledgeBaseReindexFailed(Long kbId, String error) {
    String message = safe(error);
    jdbc.update(
        "UPDATE document SET status='PARTIAL',embedding_status='FAILED',embedding_error=?,"
            + "error_message=? WHERE kb_id=? AND embedding_status='REINDEXING'",
        message,
        safe("Vector reindex failed: " + message),
        kbId);
  }

  public void markKnowledgeBaseEmbeddingStale(Long kbId, String error) {
    String message = safe(error);
    jdbc.update(
        "UPDATE document SET status='PARTIAL',embedding_status='STALE',embedding_error=?,"
            + "error_message=? WHERE kb_id=? AND status IN('SUCCESS','PARTIAL')",
        message,
        safe("Vector index requires rebuild: " + message),
        kbId);
  }

  public List<Long> staleProcessingIds(int leaseMinutes) {
    return jdbc.query(
        "SELECT id FROM document WHERE status='PROCESSING' "
            + "AND (lease_until IS NULL OR lease_until < NOW()) "
            + "AND (heartbeat_at IS NULL OR heartbeat_at < DATE_SUB(NOW(), INTERVAL ? MINUTE)) "
            + "ORDER BY updated_at LIMIT 50",
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

  @Transactional
  public boolean releaseStaleProcessing(Long id, int leaseMinutes) {
    jdbc.update(
        "UPDATE document_revision r JOIN document d ON d.id=r.document_id "
            + "SET r.status='FAILED',r.error_message='Parse lease expired' "
            + "WHERE d.id=? AND r.parse_job_id=d.parse_job_id AND r.status='PROCESSING'",
        id);
    return jdbc.update(
            "UPDATE document SET status=CASE WHEN active_revision>0 THEN 'PARTIAL' ELSE 'PENDING' END,"
                + "parse_job_id=NULL,lease_until=NULL,heartbeat_at=NULL,"
                + "error_message='上次解析任务超时，已恢复为待重试' "
                + "WHERE id=? AND status='PROCESSING' "
                + "AND (lease_until IS NULL OR lease_until < NOW()) AND (heartbeat_at IS NULL OR heartbeat_at < DATE_SUB(NOW(), INTERVAL ? MINUTE))",
            id,
            leaseMinutes)
        == 1;
  }

  public int countChunks(Long kbId) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM document_chunk c JOIN document d ON d.id=c.document_id "
            + "WHERE d.kb_id=? AND c.revision_no=d.active_revision "
            + "AND d.status<>'DELETED' AND d.active_revision>0",
        new Object[] {kbId}, Integer.class);
    return count == null ? 0 : count;
  }

  public void delete(Long id) {
    jdbc.update(
        "DELETE t FROM document_chunk_term t JOIN document_chunk c ON c.id=t.chunk_id WHERE c.document_id=?",
        id);
    jdbc.update("DELETE FROM document_chunk WHERE document_id=?", id);
    jdbc.update("UPDATE document SET status='DELETED',deleted_at=NOW(),parse_job_id=NULL,lease_until=NULL,heartbeat_at=NULL WHERE id=?", id);
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
    d.setEmbeddingStatus(r.getString("embedding_status"));
    d.setEmbeddingError(r.getString("embedding_error"));
    d.setCreatedAt(r.getTimestamp("created_at").toLocalDateTime());
    return d;
  }

  private Object[] lexicalArguments(Long kbId, List<String> terms) {
    List<Object> values = new ArrayList<Object>();
    values.add(kbId);
    values.addAll(terms);
    values.add(kbId);
    values.addAll(terms);
    return values.toArray();
  }

  private String placeholders(int count) {
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) value.append(',');
      value.append('?');
    }
    return value.toString();
  }

  private String safe(String message) {
    String value = message == null ? "unknown error" : message;
    return value.length() > 480 ? value.substring(0, 480) : value;
  }
}
