ALTER TABLE user_fatigue_profile
  ADD COLUMN last_weight_calibrated_at DATETIME NULL,
  ADD COLUMN survey_snoozed_until DATETIME NULL,
  ADD COLUMN survey_skipped_date DATE NULL,
  ADD COLUMN alert_suppressed_date DATE NULL,
  ADD COLUMN alert_suppressed_band VARCHAR(20) NULL;

ALTER TABLE fatigue_survey
  ADD COLUMN learning_weight DECIMAL(4,3) NOT NULL DEFAULT 1,
  ADD COLUMN completed_level_counts JSON NULL;

CREATE INDEX idx_fatigue_survey_retention
  ON fatigue_survey(local_date);
