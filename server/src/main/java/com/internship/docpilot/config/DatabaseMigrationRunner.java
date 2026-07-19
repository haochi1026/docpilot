package com.internship.docpilot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("legacy-migration")
public class DatabaseMigrationRunner implements CommandLineRunner {
  private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
  private final JdbcTemplate jdbc;

  public DatabaseMigrationRunner(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void run(String... args) {
    addColumnIfMissing(
        "outbox_message",
        "claimed_at",
        "ALTER TABLE outbox_message ADD COLUMN claimed_at DATETIME NULL AFTER retry_count");
    addColumnIfMissing(
        "outbox_message",
        "claimed_by",
        "ALTER TABLE outbox_message ADD COLUMN claimed_by VARCHAR(80) NULL AFTER claimed_at");
    addColumnIfMissing(
        "document",
        "embedding_status",
        "ALTER TABLE document ADD COLUMN embedding_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER error_message");
    addColumnIfMissing(
        "document",
        "embedding_error",
        "ALTER TABLE document ADD COLUMN embedding_error VARCHAR(500) NULL AFTER embedding_status");
    addColumnIfMissing(
        "document",
        "extraction_method",
        "ALTER TABLE document ADD COLUMN extraction_method VARCHAR(20) NULL AFTER embedding_error");
    addColumnIfMissing(
        "document",
        "page_count",
        "ALTER TABLE document ADD COLUMN page_count INT NULL AFTER extraction_method");
    addColumnIfMissing(
        "document_chunk",
        "heading",
        "ALTER TABLE document_chunk ADD COLUMN heading VARCHAR(255) NULL AFTER page_no");
    addColumnIfMissing(
        "document_chunk",
        "token_count",
        "ALTER TABLE document_chunk ADD COLUMN token_count INT NOT NULL DEFAULT 0 AFTER heading");
    addColumnIfMissing(
        "document_chunk",
        "lexical_indexed",
        "ALTER TABLE document_chunk ADD COLUMN lexical_indexed TINYINT(1) NOT NULL DEFAULT 0 AFTER token_count");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS document_chunk_term ("
            + "chunk_id BIGINT NOT NULL,kb_id BIGINT NOT NULL,term_value VARCHAR(64) NOT NULL,"
            + "term_frequency INT NOT NULL,PRIMARY KEY(chunk_id,term_value),"
            + "INDEX idx_chunk_term_lookup(kb_id,term_value,chunk_id))");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS agent_approval ("
            + "id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,conversation_id BIGINT NOT NULL,"
            + "kb_id BIGINT NOT NULL,thread_id VARCHAR(160) NOT NULL,tool_name VARCHAR(120) NOT NULL,"
            + "resource_id BIGINT NOT NULL,action_payload MEDIUMTEXT NOT NULL,"
            + "status VARCHAR(20) NOT NULL DEFAULT 'PENDING',expires_at DATETIME NOT NULL,"
            + "decided_at DATETIME NULL,consumed_at DATETIME NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "INDEX idx_agent_approval_pending(user_id,conversation_id,status,expires_at))");
  }

  private void addColumnIfMissing(String table, String column, String sql) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
            Integer.class,
            table,
            column);
    if (count != null && count == 0) {
      jdbc.execute(sql);
      log.info("Applied database migration: add {}.{}", table, column);
    }
  }

}
