ALTER TABLE notification_preference
  ADD COLUMN fatigue_alert_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN fatigue_survey_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN quiet_start_time TIME NULL,
  ADD COLUMN quiet_end_time TIME NULL;

ALTER TABLE notification
  ADD COLUMN local_date DATE NULL,
  ADD COLUMN target_route VARCHAR(200) NULL,
  ADD COLUMN data_revision BIGINT NULL;

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
  last_calibrated_at DATETIME NULL,
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
  external_factor_tags JSON NULL,
  completed_load_snapshot DECIMAL(10,3) NOT NULL DEFAULT 0,
  weights_snapshot JSON NOT NULL,
  capacity_before DECIMAL(10,3) NOT NULL DEFAULT 18,
  capacity_after DECIMAL(10,3) NULL,
  model_eligible BOOLEAN NOT NULL DEFAULT FALSE,
  ineligible_reason VARCHAR(100) NULL,
  algorithm_version INT NOT NULL DEFAULT 1,
  submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, local_date)
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

CREATE INDEX idx_fatigue_summary_user_date ON fatigue_daily_summary(user_id, local_date);
CREATE INDEX idx_fatigue_survey_user_date ON fatigue_survey(user_id, local_date);
CREATE INDEX idx_fatigue_alert_user_date ON fatigue_alert_log(user_id, local_date);
