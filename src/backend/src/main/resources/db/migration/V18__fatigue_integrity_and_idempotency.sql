ALTER TABLE schedule
  ADD CONSTRAINT chk_schedule_urgency_level CHECK (urgency_level BETWEEN 1 AND 5),
  ADD CONSTRAINT chk_schedule_fatigue_level CHECK (fatigue_level BETWEEN 1 AND 5),
  ADD CONSTRAINT chk_schedule_completed_fatigue_level CHECK (completed_fatigue_level IS NULL OR completed_fatigue_level BETWEEN 1 AND 5),
  ADD CONSTRAINT chk_schedule_completed_fatigue_weight CHECK (completed_fatigue_weight IS NULL OR completed_fatigue_weight > 0);

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

CREATE INDEX idx_fatigue_survey_skip_date
  ON fatigue_survey_skip(local_date);

CREATE INDEX idx_fatigue_survey_prompt_date
  ON fatigue_survey_prompt_log(local_date);
