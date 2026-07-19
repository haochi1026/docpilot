ALTER TABLE document ADD COLUMN active_revision INT NOT NULL DEFAULT 0 AFTER version;
ALTER TABLE document_chunk ADD COLUMN revision_no INT NOT NULL DEFAULT 0 AFTER document_id;
ALTER TABLE document_chunk ADD UNIQUE KEY uk_document_chunk_revision(document_id,revision_no,chunk_no);
-- Add the replacement index before dropping the legacy one: MySQL uses an
-- index beginning with document_id to enforce fk_chunk_document.
ALTER TABLE document_chunk DROP INDEX uk_document_chunk;

UPDATE document_chunk c
JOIN document d ON d.id=c.document_id
SET c.revision_no=d.version;

UPDATE document d
SET d.active_revision=d.version
WHERE EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_id=d.id);

CREATE TABLE document_revision (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  revision_no INT NOT NULL,
  parse_job_id VARCHAR(80) NULL,
  status VARCHAR(20) NOT NULL,
  extraction_method VARCHAR(20) NULL,
  page_count INT NULL,
  embedding_status VARCHAR(20) NULL,
  embedding_model VARCHAR(120) NULL,
  error_message VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at DATETIME NULL,
  UNIQUE KEY uk_document_revision(document_id,revision_no),
  UNIQUE KEY uk_document_revision_job(parse_job_id),
  INDEX idx_document_revision_status(document_id,status,created_at),
  CONSTRAINT fk_revision_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE
);

INSERT INTO document_revision(
  document_id,revision_no,status,extraction_method,page_count,
  embedding_status,embedding_model,error_message,created_at,published_at
)
SELECT d.id,d.active_revision,
       CASE WHEN d.status IN('SUCCESS','PARTIAL') THEN 'PUBLISHED' ELSE d.status END,
       d.extraction_method,d.page_count,d.embedding_status,d.embedding_model,
       d.error_message,d.created_at,
       CASE WHEN d.active_revision>0 THEN d.updated_at ELSE NULL END
FROM document d
WHERE d.active_revision>0;
