ALTER TABLE schedule
  ADD COLUMN urgency_level TINYINT NOT NULL DEFAULT 3,
  ADD COLUMN fatigue_level TINYINT NOT NULL DEFAULT 3,
  ADD COLUMN completed_at DATETIME NULL,
  ADD COLUMN completed_fatigue_level TINYINT NULL,
  ADD COLUMN completed_fatigue_weight DECIMAL(8,3) NULL;

CREATE INDEX idx_schedule_user_urgency
  ON schedule(user_id, urgency_level, status);

CREATE INDEX idx_schedule_user_fatigue
  ON schedule(user_id, fatigue_level, status);

CREATE INDEX idx_schedule_user_completed_at
  ON schedule(user_id, completed_at);
