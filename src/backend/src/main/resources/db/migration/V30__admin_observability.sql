CREATE TABLE admin_analytics_state (
  id TINYINT NOT NULL PRIMARY KEY,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO admin_analytics_state (id) VALUES (1);

CREATE TABLE user_activity_daily (
  user_id BIGINT NOT NULL,
  activity_date DATE NOT NULL,
  PRIMARY KEY (user_id, activity_date),
  KEY idx_activity_date (activity_date, user_id)
);

CREATE TABLE security_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NULL,
  account_hash VARCHAR(64) NULL,
  event_type VARCHAR(24) NOT NULL,
  ip_address VARCHAR(45) NOT NULL,
  ip_segment VARCHAR(64) NOT NULL,
  risk_level VARCHAR(12) NOT NULL DEFAULT 'low',
  reason VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_security_created (created_at, event_type),
  KEY idx_security_account (account_hash, created_at),
  KEY idx_security_ip (ip_address, created_at),
  KEY idx_security_user (user_id, created_at)
);

ALTER TABLE ai_usage_log ADD COLUMN model_name VARCHAR(100) NULL;
ALTER TABLE ai_usage_log ADD COLUMN input_tokens BIGINT NULL;
ALTER TABLE ai_usage_log ADD COLUMN output_tokens BIGINT NULL;
ALTER TABLE ai_usage_log ADD COLUMN estimated_cost DECIMAL(18,8) NULL;
ALTER TABLE ai_usage_log ADD COLUMN key_id BIGINT NULL;
ALTER TABLE ai_usage_log ADD COLUMN switch_count INT NOT NULL DEFAULT 0;

CREATE TABLE ai_key_call_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  key_id BIGINT NULL,
  success BOOLEAN NOT NULL,
  switched BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_key_call_created (created_at, key_id)
);

CREATE TABLE ai_quota_setting (
  id TINYINT NOT NULL PRIMARY KEY,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  global_daily_limit INT NOT NULL DEFAULT 10000,
  user_daily_limit INT NOT NULL DEFAULT 100,
  team_daily_limit INT NOT NULL DEFAULT 1000,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_by BIGINT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO ai_quota_setting (id) VALUES (1);

CREATE TABLE ai_model_price (
  model_name VARCHAR(100) NOT NULL PRIMARY KEY,
  input_per_million DECIMAL(18,6) NOT NULL,
  output_per_million DECIMAL(18,6) NOT NULL
);

CREATE TABLE ai_quota_daily (
  scope_type VARCHAR(12) NOT NULL,
  subject_id BIGINT NOT NULL,
  usage_date DATE NOT NULL,
  calls INT NOT NULL DEFAULT 0,
  PRIMARY KEY (scope_type, subject_id, usage_date)
);

CREATE TABLE ai_quota_alert (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  scope_type VARCHAR(12) NOT NULL,
  subject_id BIGINT NOT NULL,
  daily_limit INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_quota_alert_created (created_at)
);

CREATE INDEX idx_user_created ON `user` (created_at);
CREATE INDEX idx_survey_date_score ON fatigue_survey (local_date, score);
CREATE INDEX idx_summary_date ON fatigue_daily_summary (local_date, user_id);
CREATE INDEX idx_task_created ON team_task (created_at);
