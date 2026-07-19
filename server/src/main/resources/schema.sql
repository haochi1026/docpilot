CREATE TABLE IF NOT EXISTS app_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'USER',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_base (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  owner_id BIGINT NOT NULL,
  visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS kb_acl (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  kb_id BIGINT NOT NULL,
  subject_type VARCHAR(20) NOT NULL,
  subject_id BIGINT NOT NULL,
  permission VARCHAR(20) NOT NULL DEFAULT 'READ',
  UNIQUE KEY uk_kb_subject(kb_id,subject_type,subject_id)
);

CREATE TABLE IF NOT EXISTS department (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL UNIQUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS department_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  department_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  member_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_department_user(department_id,user_id)
);

CREATE TABLE IF NOT EXISTS kb_department (
  kb_id BIGINT PRIMARY KEY,
  department_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_kb_department_department(department_id)
);

CREATE TABLE IF NOT EXISTS kb_permission_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  kb_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  target_user_id BIGINT,
  action_type VARCHAR(30) NOT NULL,
  permission VARCHAR(20),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_kb_permission_audit(kb_id,created_at)
);

CREATE TABLE IF NOT EXISTS document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  kb_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  content_type VARCHAR(120),
  size_bytes BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  error_message VARCHAR(500),
  embedding_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  embedding_error VARCHAR(500),
  extraction_method VARCHAR(20) NULL,
  page_count INT NULL,
  version INT NOT NULL DEFAULT 1,
  active_revision INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_document_kb_status(kb_id,status)
);

CREATE TABLE IF NOT EXISTS document_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  revision_no INT NOT NULL DEFAULT 0,
  chunk_no INT NOT NULL,
  page_no INT NULL,
  heading VARCHAR(255) NULL,
  token_count INT NOT NULL DEFAULT 0,
  lexical_indexed TINYINT(1) NOT NULL DEFAULT 0,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_document_chunk_revision(document_id,revision_no,chunk_no),
  FULLTEXT KEY ft_chunk_content(content)
);

CREATE TABLE IF NOT EXISTS document_chunk_term (
  chunk_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,
  term_value VARCHAR(64) NOT NULL,
  term_frequency INT NOT NULL,
  PRIMARY KEY(chunk_id,term_value),
  INDEX idx_chunk_term_lookup(kb_id,term_value,chunk_id)
);

CREATE TABLE IF NOT EXISTS document_revision (
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
  UNIQUE KEY uk_document_revision_job(parse_job_id)
);

CREATE TABLE IF NOT EXISTS outbox_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL UNIQUE,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload VARCHAR(1000) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  claimed_at DATETIME NULL,
  claimed_by VARCHAR(80) NULL,
  next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_outbox_poll(status,next_retry_at)
);

CREATE TABLE IF NOT EXISTS chat_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,
  question VARCHAR(1000) NOT NULL,
  source_count INT NOT NULL DEFAULT 0,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_conversation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  kb_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  title VARCHAR(80) NOT NULL DEFAULT '新对话',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_conversation_user_kb_time(user_id,kb_id,updated_at)
);

CREATE TABLE IF NOT EXISTS chat_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  sources_json MEDIUMTEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_message_conversation(conversation_id,id)
);

CREATE TABLE IF NOT EXISTS agent_approval (
  id VARCHAR(36) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,
  thread_id VARCHAR(160) NOT NULL,
  tool_name VARCHAR(120) NOT NULL,
  resource_id BIGINT NOT NULL,
  action_payload MEDIUMTEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  expires_at DATETIME NOT NULL,
  decided_at DATETIME NULL,
  consumed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_agent_approval_pending(user_id,conversation_id,status,expires_at)
);

CREATE TABLE IF NOT EXISTS system_setting (
  setting_key VARCHAR(80) PRIMARY KEY,
  setting_value VARCHAR(500) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
