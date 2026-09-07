DROP TABLE IF EXISTS ai_config;
DROP TABLE IF EXISTS ai_api_key;
DROP TABLE IF EXISTS ai_key_pool_state;
DROP TABLE IF EXISTS ai_usage_log;
DROP TABLE IF EXISTS auth_revoked_access_token;
DROP TABLE IF EXISTS auth_refresh_token;
DROP TABLE IF EXISTS auth_email_otp;
DROP TABLE IF EXISTS registration_setting;
DROP TABLE IF EXISTS admin_operation_log;
DROP TABLE IF EXISTS admin_user;
DROP TABLE IF EXISTS notification;
DROP TABLE IF EXISTS fatigue_alert_log;
DROP TABLE IF EXISTS fatigue_survey_prompt_log;
DROP TABLE IF EXISTS fatigue_survey_skip;
DROP TABLE IF EXISTS fatigue_survey;
DROP TABLE IF EXISTS fatigue_daily_summary;
DROP TABLE IF EXISTS user_fatigue_profile;
DROP TABLE IF EXISTS notification_preference;
DROP TABLE IF EXISTS reminder_preset;
DROP TABLE IF EXISTS reminder;
DROP TABLE IF EXISTS team_task_reminder_plan;
DROP TABLE IF EXISTS team_task_event;
DROP TABLE IF EXISTS team_task_assignee;
DROP TABLE IF EXISTS team_task;
DROP TABLE IF EXISTS schedule;
DROP TABLE IF EXISTS schedule_series_exdate;
DROP TABLE IF EXISTS task_group;
DROP TABLE IF EXISTS team_member;
DROP TABLE IF EXISTS team;
DROP TABLE IF EXISTS `user`;

CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR "com.dayliane.TestSqlFunctions.utcTimestamp";

CREATE TABLE `user` (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  phone VARCHAR(20) UNIQUE,
  email VARCHAR(254),
  email_verified_at DATETIME,
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(50) NOT NULL,
  avatar_url VARCHAR(500),
  timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Shanghai',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  token_version INT NOT NULL DEFAULT 0,
  profile_version BIGINT NOT NULL DEFAULT 0,
  subscribe_token VARCHAR(64),
  deleted_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_user_email ON `user`(email);
CREATE UNIQUE INDEX uk_user_subscribe_token ON `user`(subscribe_token);

CREATE TABLE auth_email_otp (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  email VARCHAR(254) NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  code_digest VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'issued',
  attempt_count TINYINT NOT NULL DEFAULT 0,
  max_attempts TINYINT NOT NULL DEFAULT 5,
  sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME,
  invalidated_at DATETIME,
  request_ip VARCHAR(45),
  user_agent TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_auth_email_otp_lookup ON auth_email_otp(email, purpose, status, expires_at);
CREATE INDEX idx_auth_email_otp_user_status ON auth_email_otp(user_id, status);
CREATE INDEX idx_auth_email_otp_expires_at ON auth_email_otp(expires_at);
CREATE INDEX idx_auth_email_otp_email_sent_at ON auth_email_otp(email, sent_at);
CREATE INDEX idx_auth_email_otp_ip_sent_at ON auth_email_otp(request_ip, sent_at);

CREATE TABLE auth_refresh_token (
  jti VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE auth_revoked_access_token (
  jti VARCHAR(64) NOT NULL PRIMARY KEY,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  invite_code VARCHAR(10) NOT NULL UNIQUE,
  invite_code_expire_at DATETIME NOT NULL,
  owner_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  deleted_at DATETIME,
  deleted_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_member (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  team_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'member',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  removed_at DATETIME,
  removed_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (team_id, user_id)
);

CREATE TABLE task_group (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  team_id BIGINT,
  scope VARCHAR(20) NOT NULL,
  name VARCHAR(50) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE schedule (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  group_name VARCHAR(50) NOT NULL DEFAULT 'Default',
  group_id BIGINT,
  sort_order INT NOT NULL DEFAULT 0,
  time_type VARCHAR(30) NOT NULL,
  start_time DATETIME,
  end_time DATETIME,
  deadline_time DATETIME,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  urgency_level TINYINT NOT NULL DEFAULT 3,
  fatigue_level TINYINT NOT NULL DEFAULT 3,
  completed_at DATETIME,
  completed_fatigue_level TINYINT,
  completed_fatigue_weight DECIMAL(8,3),
  rrule VARCHAR(500),
  series_id VARCHAR(36),
  occurrence_date DATE,
  deleted_at DATETIME,
  deleted_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_schedule_urgency_level CHECK (urgency_level BETWEEN 1 AND 5),
  CONSTRAINT chk_schedule_fatigue_level CHECK (fatigue_level BETWEEN 1 AND 5),
  CONSTRAINT chk_schedule_completed_fatigue_level CHECK (completed_fatigue_level IS NULL OR completed_fatigue_level BETWEEN 1 AND 5),
  CONSTRAINT chk_schedule_completed_fatigue_weight CHECK (completed_fatigue_weight IS NULL OR completed_fatigue_weight > 0)
);

CREATE INDEX idx_schedule_series_id ON schedule(series_id, occurrence_date);
CREATE UNIQUE INDEX uk_schedule_series_occurrence ON schedule(series_id, occurrence_date);

CREATE TABLE schedule_series_exdate (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  series_id VARCHAR(36) NOT NULL,
  excluded_date DATE NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_series_exdate UNIQUE (series_id, excluded_date)
);

CREATE TABLE reminder (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  target_type VARCHAR(30) NOT NULL,
  target_id BIGINT NOT NULL,
  remind_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  sent_at DATETIME,
  error_message TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_task (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  team_id BIGINT NOT NULL,
  creator_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  group_name VARCHAR(50),
  group_id BIGINT,
  sort_order INT NOT NULL DEFAULT 0,
  start_time DATETIME,
  deadline_time DATETIME,
  status VARCHAR(30) NOT NULL DEFAULT 'active',
  approval_status VARCHAR(20) NOT NULL DEFAULT 'approved',
  reviewed_by BIGINT,
  reviewed_at DATETIME,
  unassigned_count INT NOT NULL DEFAULT 0,
  updated_by BIGINT,
  time_updated_at DATETIME,
  deleted_at DATETIME,
  deleted_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_task_assignee (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  assign_round INT NOT NULL DEFAULT 1,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  active_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_active THEN user_id ELSE NULL END),
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  accepted_at DATETIME,
  rejected_at DATETIME,
  completed_at DATETIME,
  completed_fatigue_level TINYINT,
  completed_fatigue_weight DECIMAL(8,3),
  reassigned_from_user_id BIGINT,
  assigned_by BIGINT,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status_updated_by BIGINT,
  status_updated_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (task_id, user_id, assign_round),
  UNIQUE (task_id, active_user_id),
  CONSTRAINT chk_team_assignee_completed_fatigue_level CHECK (completed_fatigue_level IS NULL OR completed_fatigue_level BETWEEN 1 AND 5),
  CONSTRAINT chk_team_assignee_completed_fatigue_weight CHECK (completed_fatigue_weight IS NULL OR completed_fatigue_weight > 0)
);

CREATE INDEX idx_team_assignee_user_completed_at
  ON team_task_assignee(user_id, completed_at, completed_fatigue_weight);

CREATE TABLE team_task_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  actor_id BIGINT,
  actor_type VARCHAR(20) NOT NULL DEFAULT 'user',
  event_type VARCHAR(50) NOT NULL,
  content VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_task_reminder_plan (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  remind_at DATETIME NOT NULL,
  UNIQUE (task_id, remind_at)
);

CREATE TABLE notification (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(50) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content TEXT,
  related_type VARCHAR(30),
  related_id BIGINT,
  reminder_id BIGINT,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  read_at DATETIME,
  local_date DATE,
  target_route VARCHAR(200),
  data_revision BIGINT,
  deleted_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notification_preference (
  user_id BIGINT NOT NULL PRIMARY KEY,
  browser_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  task_assigned_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  task_status_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  fatigue_alert_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  fatigue_survey_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  quiet_start_time TIME,
  quiet_end_time TIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_fatigue_profile (
  user_id BIGINT NOT NULL PRIMARY KEY,
  level_1_weight DECIMAL(8,3) NOT NULL DEFAULT 1,
  level_2_weight DECIMAL(8,3) NOT NULL DEFAULT 2,
  level_3_weight DECIMAL(8,3) NOT NULL DEFAULT 3,
  level_4_weight DECIMAL(8,3) NOT NULL DEFAULT 5,
  level_5_weight DECIMAL(8,3) NOT NULL DEFAULT 8,
  capacity_75 DECIMAL(8,3) NOT NULL DEFAULT 18,
  model_stage VARCHAR(20) NOT NULL DEFAULT 'default',
  valid_survey_days INT NOT NULL DEFAULT 0,
  algorithm_version INT NOT NULL DEFAULT 1,
  fatigue_tracking_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  fatigue_alert_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  survey_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  survey_time TIME NOT NULL DEFAULT '21:30:00',
  capacity_locked BOOLEAN NOT NULL DEFAULT FALSE,
  data_revision BIGINT NOT NULL DEFAULT 0,
  last_calibrated_at DATETIME,
  last_weight_calibrated_at DATETIME,
  survey_snoozed_until DATETIME,
  survey_skipped_date DATE,
  alert_suppressed_date DATE,
  alert_suppressed_band VARCHAR(20),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE fatigue_daily_summary (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  local_date DATE NOT NULL,
  timezone_snapshot VARCHAR(64) NOT NULL,
  planned_load DECIMAL(10,3) NOT NULL DEFAULT 0,
  completed_load DECIMAL(10,3) NOT NULL DEFAULT 0,
  predicted_score SMALLINT NOT NULL DEFAULT 0,
  pending_count INT NOT NULL DEFAULT 0,
  completed_count INT NOT NULL DEFAULT 0,
  algorithm_version INT NOT NULL DEFAULT 1,
  data_revision BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, local_date)
);

CREATE TABLE fatigue_survey (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  local_date DATE NOT NULL,
  timezone_snapshot VARCHAR(64) NOT NULL,
  score TINYINT NOT NULL,
  external_factor_level TINYINT NOT NULL DEFAULT 0,
  external_factor_tags VARCHAR(2000),
  completed_load_snapshot DECIMAL(10,3) NOT NULL DEFAULT 0,
  weights_snapshot VARCHAR(500) NOT NULL,
  capacity_before DECIMAL(10,3) NOT NULL DEFAULT 18,
  capacity_after DECIMAL(10,3),
  model_eligible BOOLEAN NOT NULL DEFAULT FALSE,
  ineligible_reason VARCHAR(100),
  learning_weight DECIMAL(4,3) NOT NULL DEFAULT 1,
  completed_level_counts JSON,
  algorithm_version INT NOT NULL DEFAULT 1,
  submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, local_date)
);

CREATE TABLE fatigue_survey_skip (
  user_id BIGINT NOT NULL,
  local_date DATE NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, local_date)
);

CREATE TABLE fatigue_survey_prompt_log (
  user_id BIGINT NOT NULL,
  local_date DATE NOT NULL,
  algorithm_version INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, local_date)
);

CREATE TABLE fatigue_alert_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  local_date DATE NOT NULL,
  source_type VARCHAR(20) NOT NULL,
  threshold_band VARCHAR(20) NOT NULL,
  score_snapshot SMALLINT NOT NULL,
  algorithm_version INT NOT NULL DEFAULT 1,
  notified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, local_date, source_type, threshold_band, algorithm_version)
);

CREATE TABLE reminder_preset (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  offset_minutes INT NOT NULL,
  sort_order INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, offset_minutes)
);

CREATE TABLE admin_user (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'admin',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  last_login_at DATETIME,
  last_login_ip VARCHAR(45),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE registration_setting (
  id TINYINT NOT NULL PRIMARY KEY,
  registration_enabled BOOLEAN NOT NULL,
  updated_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_registration_setting_singleton CHECK (id = 1)
);

CREATE TABLE admin_operation_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT NOT NULL,
  action VARCHAR(50) NOT NULL,
  target_type VARCHAR(30),
  target_id BIGINT,
  before_data JSON,
  after_data JSON,
  ip_address VARCHAR(45),
  user_agent TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_usage_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  feature_type VARCHAR(30) NOT NULL,
  input_text TEXT,
  output_text TEXT,
  status VARCHAR(20) NOT NULL,
  error_message TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_config (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  provider VARCHAR(30) NOT NULL,
  model_name VARCHAR(50) NOT NULL,
  api_base_url VARCHAR(500),
  api_key_masked VARCHAR(100),
  api_key_ciphertext TEXT,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  remark TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_api_key (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  api_key_masked VARCHAR(100) NOT NULL,
  api_key_ciphertext TEXT NOT NULL,
  api_base_url VARCHAR(500),
  priority INT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  remark TEXT,
  last_test_status VARCHAR(20),
  last_tested_at DATETIME,
  last_error VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_api_key_order ON ai_api_key(priority, id);
CREATE INDEX idx_ai_api_key_enabled ON ai_api_key(enabled);

CREATE TABLE ai_key_pool_state (
  id TINYINT NOT NULL PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ai_key_pool_state (id, revision) VALUES (1, 0);
