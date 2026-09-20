-- P02：为每日进度任务补充「首次达到 100% 的用户时区日期」。
-- completed_at 继续保存实际提交/修正的操作时间，普通一次性任务逻辑不变。
ALTER TABLE schedule
  ADD COLUMN progress_completed_date DATE NULL;

CREATE INDEX idx_schedule_user_progress_completed
  ON schedule(user_id, progress_completed_date);

-- 回填一：从每日记录中找出累计首次达到 100% 的日期（进度任务的权威来源）。
UPDATE schedule
SET progress_completed_date = (
  SELECT MIN(p.progress_date)
  FROM schedule_progress_daily p
  WHERE p.schedule_id = schedule.id AND p.cumulative_progress >= 100
)
WHERE progress_tracking_enabled = TRUE;

-- 回填二：无法从每日记录推导时，退化为 completed_at 的日期（best-effort，历史数据兼容）。
UPDATE schedule
SET progress_completed_date = DATE(completed_at)
WHERE progress_tracking_enabled = TRUE
  AND progress_completed_date IS NULL
  AND completed_at IS NOT NULL;
