package com.internship.docpilot.repository;

import com.internship.docpilot.model.RetrievalCandidate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.sql.Connection;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VectorStoreRepository {
  private static final Logger log = LoggerFactory.getLogger(VectorStoreRepository.class);
  private final boolean enabled;
  private final int dimension;
  private final int candidateLimit;
  private final String tableName;
  private final HikariDataSource dataSource;
  private final JdbcTemplate jdbc;

  public VectorStoreRepository(
      @Value("${app.vector-store.enabled:true}") boolean enabled,
      @Value("${app.vector-store.url}") String url,
      @Value("${app.vector-store.username}") String username,
      @Value("${app.vector-store.password}") String password,
      @Value("${app.embedding.dimension:1024}") int dimension,
      @Value("${app.vector-store.candidate-limit:80}") int candidateLimit) {
    this.enabled = enabled;
    this.dimension = dimension;
    this.candidateLimit = Math.max(10, candidateLimit);
    this.tableName = "document_chunk_vector_" + Math.max(1, dimension);
    if (!enabled) {
      this.dataSource = null;
      this.jdbc = null;
      return;
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(8);
    config.setMinimumIdle(1);
    config.setPoolName("docpilot-vector");
    config.setConnectionTimeout(10000);
    this.dataSource = new HikariDataSource(config);
    this.jdbc = new JdbcTemplate(dataSource);
    initialize();
  }

  private void initialize() {
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS " + tableName + " ("
            + "chunk_id BIGINT NOT NULL,document_id BIGINT NOT NULL,kb_id BIGINT NOT NULL,"
            + "model_name VARCHAR(120) NOT NULL,embedding vector("
            + dimension
            + ") NOT NULL,updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
            + "PRIMARY KEY(chunk_id,model_name))");
    jdbc.execute(
        "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_kb_model ON " + tableName + "(kb_id,model_name)");
    jdbc.execute(
        "CREATE INDEX IF NOT EXISTS idx_" + tableName + "_hnsw ON " + tableName + " "
            + "USING hnsw (embedding vector_cosine_ops)");
    Integer configured = jdbc.queryForObject(
        "SELECT atttypmod FROM pg_attribute WHERE attrelid=?::regclass AND attname='embedding'",
        Integer.class,
        tableName);
    if (configured != null && configured > 0 && configured != dimension) {
      throw new IllegalStateException(
          "Embedding dimension mismatch between database (" + configured + ") and application (" + dimension + "); migrate vectors explicitly");
    }
    migrateCompatibleLegacyTable();
  }

  /**
   * Import vectors written by the pre-dimensioned schema exactly once.
   *
   * <p>The old deployment stored the same columns in {@code document_chunk_vector}. Merely
   * switching the application to {@code document_chunk_vector_<dimension>} made existing
   * documents look READY while the active table was empty. The migration is transactional,
   * dimension-checked and recorded in PostgreSQL so deleted legacy rows cannot be re-imported on
   * a later restart.
   */
  private void migrateCompatibleLegacyTable() {
    final String migrationKey = "legacy_document_chunk_vector_to_" + dimension + "_v1";
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS docpilot_vector_migration ("
            + "migration_key VARCHAR(160) PRIMARY KEY,applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
            + "migrated_rows INTEGER NOT NULL DEFAULT 0)");
    Boolean legacyExists =
        jdbc.queryForObject(
            "SELECT to_regclass('public.document_chunk_vector') IS NOT NULL", Boolean.class);
    if (!Boolean.TRUE.equals(legacyExists)) return;
    Integer completed =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM docpilot_vector_migration WHERE migration_key=?",
            new Object[] {migrationKey},
            Integer.class);
    if (completed != null && completed > 0) return;
    Integer legacyDimension =
        jdbc.queryForObject(
            "SELECT atttypmod FROM pg_attribute "
                + "WHERE attrelid='document_chunk_vector'::regclass AND attname='embedding'",
            Integer.class);
    if (legacyDimension == null || legacyDimension != dimension) {
      log.warn(
          "Skipping incompatible legacy vector table: expected dimension {}, actual {}",
          dimension,
          legacyDimension);
      return;
    }
    jdbc.execute(
        (Connection connection) -> {
          boolean previousAutoCommit = connection.getAutoCommit();
          connection.setAutoCommit(false);
          try {
            int migrated;
            try (java.sql.PreparedStatement copy =
                    connection.prepareStatement(
                        "INSERT INTO "
                            + tableName
                            + "(chunk_id,document_id,kb_id,model_name,embedding,updated_at) "
                            + "SELECT chunk_id,document_id,kb_id,model_name,embedding,updated_at "
                            + "FROM document_chunk_vector ON CONFLICT(chunk_id,model_name) DO NOTHING");
                java.sql.PreparedStatement record =
                    connection.prepareStatement(
                        "INSERT INTO docpilot_vector_migration(migration_key,migrated_rows) "
                            + "VALUES(?,?) ON CONFLICT(migration_key) DO NOTHING")) {
              migrated = copy.executeUpdate();
              record.setString(1, migrationKey);
              record.setInt(2, migrated);
              record.executeUpdate();
            }
            connection.commit();
            log.info(
                "Imported {} vectors from legacy table into {}", migrated, tableName);
          } catch (Exception error) {
            connection.rollback();
            throw error;
          } finally {
            connection.setAutoCommit(previousAutoCommit);
          }
          return null;
        });
  }

  public boolean enabled() {
    return enabled;
  }

  public void save(Long chunkId, Long documentId, Long kbId, String model, double[] vector) {
    requireDimension(vector);
    jdbc.update(
        "INSERT INTO " + tableName + "(chunk_id,document_id,kb_id,model_name,embedding) "
            + "VALUES(?,?,?,?,?::vector) ON CONFLICT(chunk_id,model_name) DO UPDATE SET "
            + "document_id=EXCLUDED.document_id,kb_id=EXCLUDED.kb_id,embedding=EXCLUDED.embedding,updated_at=NOW()",
        chunkId,
        documentId,
        kbId,
        model,
        literal(vector));
  }

  public List<RetrievalCandidate> search(Long kbId, String model, double[] vector) {
    if (!enabled) return Collections.emptyList();
    requireDimension(vector);
    String value = literal(vector);
    return jdbc.query(
        "SELECT chunk_id,1-(embedding <=> ?::vector) score FROM " + tableName + " "
            + "WHERE kb_id=? AND model_name=? ORDER BY embedding <=> ?::vector LIMIT ?",
        new Object[] {value, kbId, model, value, candidateLimit},
        (r, i) -> new RetrievalCandidate(r.getLong("chunk_id"), r.getDouble("score")));
  }

  public int count(Long kbId, String model) {
    if (!enabled) return 0;
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + tableName + " WHERE kb_id=? AND model_name=?",
            new Object[] {kbId, model},
            Integer.class);
    return count == null ? 0 : count;
  }

  public void deleteDocument(Long documentId) {
    if (enabled) jdbc.update("DELETE FROM " + tableName + " WHERE document_id=?", documentId);
  }

  public void deleteDocumentModel(Long documentId, String model) {
    if (enabled) {
      jdbc.update(
          "DELETE FROM " + tableName + " WHERE document_id=? AND model_name=?",
          documentId,
          model);
    }
  }

  public int countDocumentModel(Long documentId, String model) {
    if (!enabled) return 0;
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + tableName + " WHERE document_id=? AND model_name=?",
        new Object[] {documentId, model},
        Integer.class);
    return count == null ? 0 : count;
  }

  /** Atomically replace one document's public vectors with its complete shadow set. */
  public void promoteDocumentModel(Long documentId, String shadowModel, String targetModel) {
    if (!enabled) return;
    jdbc.execute(
        (Connection connection) -> {
          boolean previousAutoCommit = connection.getAutoCommit();
          connection.setAutoCommit(false);
          try {
            try (java.sql.PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + tableName + " WHERE document_id=? AND model_name=?");
                java.sql.PreparedStatement promote = connection.prepareStatement(
                    "UPDATE " + tableName + " SET model_name=?,updated_at=NOW() "
                        + "WHERE document_id=? AND model_name=?")) {
              delete.setLong(1, documentId);
              delete.setString(2, targetModel);
              delete.executeUpdate();
              promote.setString(1, targetModel);
              promote.setLong(2, documentId);
              promote.setString(3, shadowModel);
              promote.executeUpdate();
            }
            connection.commit();
          } catch (Exception error) {
            connection.rollback();
            throw error;
          } finally {
            connection.setAutoCommit(previousAutoCommit);
          }
          return null;
        });
  }

  public int cleanupOrphanShadows(int hours) {
    if (!enabled) return 0;
    return jdbc.update(
        "DELETE FROM " + tableName
            + " WHERE model_name LIKE '%#shadow#%' AND updated_at < NOW() - make_interval(hours => ?)",
        Math.max(1, hours));
  }

  public void deleteChunks(List<Long> chunkIds) {
    if (!enabled || chunkIds == null || chunkIds.isEmpty()) return;
    String placeholders = chunkIds.stream().map(ignored -> "?").collect(Collectors.joining(","));
    jdbc.update(
        "DELETE FROM " + tableName + " WHERE chunk_id IN (" + placeholders + ")",
        chunkIds.toArray());
  }

  public void deleteKnowledgeBaseModel(Long kbId, String model) {
    if (enabled)
      jdbc.update(
          "DELETE FROM " + tableName + " WHERE kb_id=? AND model_name=?", kbId, model);
  }

  /** Atomically replace one public model alias with a fully built shadow set. */
  public void promoteKnowledgeBaseModel(Long kbId, String shadowModel, String targetModel) {
    if (!enabled) return;
    jdbc.execute(
        (Connection connection) -> {
          boolean previousAutoCommit = connection.getAutoCommit();
          connection.setAutoCommit(false);
          try {
            try (java.sql.PreparedStatement delete =
                    connection.prepareStatement(
                        "DELETE FROM " + tableName + " WHERE kb_id=? AND model_name=?");
                java.sql.PreparedStatement promote =
                    connection.prepareStatement(
                        "UPDATE " + tableName + " SET model_name=?,updated_at=NOW() WHERE kb_id=? AND model_name=?")) {
              delete.setLong(1, kbId);
              delete.setString(2, targetModel);
              delete.executeUpdate();
              promote.setString(1, targetModel);
              promote.setLong(2, kbId);
              promote.setString(3, shadowModel);
              promote.executeUpdate();
            }
            connection.commit();
          } catch (Exception error) {
            connection.rollback();
            throw error;
          } finally {
            connection.setAutoCommit(previousAutoCommit);
          }
          return null;
        });
  }

  private void requireDimension(double[] vector) {
    if (vector == null || vector.length != dimension) {
      throw new IllegalArgumentException(
          "Embedding dimension mismatch: expected "
              + dimension
              + ", actual "
              + (vector == null ? 0 : vector.length));
    }
  }

  private String literal(double[] vector) {
    StringBuilder value = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) value.append(',');
      value.append(Double.toString(vector[i]));
    }
    return value.append(']').toString();
  }

  @PreDestroy
  public void close() {
    if (dataSource != null) dataSource.close();
  }
}
