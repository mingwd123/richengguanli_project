-- 工单模块：配置开关、工单、消息、附件、关注、我也遇到、事件与幂等记录。
-- 遵循项目约定：时间存 UTC；不修改已执行迁移；H2 测试库使用等价写法。

CREATE TABLE IF NOT EXISTS ticket_setting (
  id TINYINT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  version BIGINT NOT NULL DEFAULT 0,
  updated_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT chk_ticket_setting_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ticket_setting (id, enabled) VALUES (1, FALSE);

CREATE TABLE IF NOT EXISTS ticket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_no VARCHAR(20) NOT NULL,
  author_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  category VARCHAR(30) NOT NULL,
  module VARCHAR(30) NOT NULL,
  description TEXT NOT NULL,
  steps TEXT NULL,
  expected_result TEXT NULL,
  environment_json TEXT NULL,
  page_path VARCHAR(500) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'open',
  priority VARCHAR(20) NOT NULL DEFAULT 'normal',
  assignee_admin_id BIGINT NULL,
  resolution TEXT NULL,
  fixed_version VARCHAR(50) NULL,
  close_reason VARCHAR(30) NULL,
  close_note TEXT NULL,
  duplicate_of_id BIGINT NULL,
  merged_at DATETIME NULL,
  pinned BOOLEAN NOT NULL DEFAULT FALSE,
  hidden_at DATETIME NULL,
  hidden_by BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  last_activity_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_no (ticket_no),
  KEY idx_ticket_status_activity (status, last_activity_at DESC, id),
  KEY idx_ticket_author (author_id, created_at DESC),
  KEY idx_ticket_assignee (assignee_admin_id),
  KEY idx_ticket_duplicate_of (duplicate_of_id),
  CONSTRAINT chk_ticket_status CHECK (status IN ('open','in_progress','waiting_reporter','resolved','closed')),
  CONSTRAINT chk_ticket_priority CHECK (priority IN ('low','normal','high','urgent')),
  CONSTRAINT chk_ticket_close_reason CHECK (close_reason IS NULL OR close_reason IN
    ('resolved','duplicate','user_withdrawn','insufficient_info','not_supported','violation','other'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ticket_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  actor_type VARCHAR(20) NOT NULL,
  actor_id BIGINT NOT NULL,
  visibility VARCHAR(20) NOT NULL DEFAULT 'public',
  content TEXT NOT NULL,
  hidden_at DATETIME NULL,
  hidden_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ticket_message_ticket (ticket_id, created_at, id),
  CONSTRAINT chk_ticket_message_actor CHECK (actor_type IN ('user','admin')),
  CONSTRAINT chk_ticket_message_visibility CHECK (visibility IN ('public','internal'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ticket_attachment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  uploader_type VARCHAR(20) NOT NULL,
  uploader_id BIGINT NOT NULL,
  ticket_id BIGINT NULL,
  message_id BIGINT NULL,
  storage_key VARCHAR(64) NOT NULL,
  original_name VARCHAR(255) NULL,
  mime_type VARCHAR(100) NOT NULL,
  size_bytes BIGINT NOT NULL,
  width INT NULL,
  height INT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'temporary',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  bound_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_attachment_key (storage_key),
  KEY idx_ticket_attachment_cleanup (state, created_at),
  KEY idx_ticket_attachment_ticket (ticket_id),
  KEY idx_ticket_attachment_message (message_id),
  CONSTRAINT chk_ticket_attachment_state CHECK (state IN ('temporary','bound')),
  CONSTRAINT chk_ticket_attachment_uploader CHECK (uploader_type IN ('user','admin'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ticket_follow (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_follow (ticket_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ticket_reaction (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reaction_type VARCHAR(20) NOT NULL DEFAULT 'same_issue',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_reaction (ticket_id, user_id, reaction_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ticket_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  actor_type VARCHAR(20) NOT NULL,
  actor_id BIGINT NULL,
  action VARCHAR(40) NOT NULL,
  from_status VARCHAR(30) NULL,
  to_status VARCHAR(30) NULL,
  note TEXT NULL,
  visibility VARCHAR(20) NOT NULL DEFAULT 'public',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ticket_event_ticket (ticket_id, created_at, id),
  CONSTRAINT chk_ticket_event_visibility CHECK (visibility IN ('public','internal'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ticket_idempotency (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  action VARCHAR(40) NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  result_json TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_idempotency (user_id, action, idempotency_key),
  KEY idx_ticket_idempotency_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
