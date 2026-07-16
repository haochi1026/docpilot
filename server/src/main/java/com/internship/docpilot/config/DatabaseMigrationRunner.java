package com.internship.docpilot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
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
    migrateChunkEmbeddingPrimaryKey();
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

  private void migrateChunkEmbeddingPrimaryKey() {
    Integer modelInPrimary =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='chunk_embedding' "
                + "AND index_name='PRIMARY' AND column_name='model_name'",
            Integer.class);
    if (modelInPrimary != null && modelInPrimary > 0) return;
    jdbc.execute("ALTER TABLE chunk_embedding DROP PRIMARY KEY, ADD PRIMARY KEY(chunk_id,model_name)");
    Integer modelIndex =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='chunk_embedding' "
                + "AND index_name='idx_chunk_embedding_model'",
            Integer.class);
    if (modelIndex != null && modelIndex == 0) {
      jdbc.execute("ALTER TABLE chunk_embedding ADD INDEX idx_chunk_embedding_model(model_name)");
    }
    log.info("Applied database migration: chunk_embedding primary key(chunk_id, model_name)");
  }
}
