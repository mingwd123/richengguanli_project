-- 个人长期任务每日进度与疲劳：仅个人日程可开启，进度按用户时区日期归集，
-- 当日完成负荷 = progressDelta / 100 × 提交时的用户权重快照。

ALTER TABLE schedule
  ADD COLUMN progress_tracking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN progress_percent DECIMAL(6,3) NOT NULL DEFAULT 0;

ALTER TABLE schedule
  ADD CONSTRAINT chk_schedule_progress_percent
    CHECK (progress_percent >= 0 AND progress_percent <= 100);

CREATE TABLE schedule_progress_daily (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  schedule_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  progress_date DATE NOT NULL,
  progress_delta DECIMAL(6,3) NOT NULL DEFAULT 0,
  cumulative_progress DECIMAL(6,3) NOT NULL DEFAULT 0,
  fatigue_level TINYINT NULL,
  fatigue_weight_snapshot DECIMAL(8,3) NULL,
  completed_load DECIMAL(10,3) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_schedule_progress_daily (schedule_id, user_id, progress_date),
  CONSTRAINT chk_schedule_progress_daily_delta CHECK (progress_delta >= 0),
  CONSTRAINT chk_schedule_progress_daily_cumulative CHECK (cumulative_progress >= 0 AND cumulative_progress <= 100),
  CONSTRAINT chk_schedule_progress_daily_level CHECK (fatigue_level IS NULL OR fatigue_level BETWEEN 1 AND 5),
  CONSTRAINT chk_schedule_progress_daily_weight CHECK (fatigue_weight_snapshot IS NULL OR fatigue_weight_snapshot > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_schedule_progress_daily_user_date
  ON schedule_progress_daily(user_id, progress_date);
