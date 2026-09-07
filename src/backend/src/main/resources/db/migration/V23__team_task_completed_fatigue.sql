ALTER TABLE team_task_assignee
  ADD COLUMN completed_fatigue_level TINYINT NULL,
  ADD COLUMN completed_fatigue_weight DECIMAL(8,3) NULL;

ALTER TABLE team_task_assignee
  ADD CONSTRAINT chk_team_assignee_completed_fatigue_level
    CHECK (completed_fatigue_level IS NULL OR completed_fatigue_level BETWEEN 1 AND 5),
  ADD CONSTRAINT chk_team_assignee_completed_fatigue_weight
    CHECK (completed_fatigue_weight IS NULL OR completed_fatigue_weight > 0);

CREATE INDEX idx_team_assignee_user_completed_at
  ON team_task_assignee(user_id, completed_at, completed_fatigue_weight);