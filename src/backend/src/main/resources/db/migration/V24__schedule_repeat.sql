-- 周期 / 重复任务：个人日程重复规则、系列标识、实例归属日期与排除日期。
-- 仅个人日程支持重复；实例按 (series_id, occurrence_date) 物化幂等。

ALTER TABLE schedule
  ADD COLUMN rrule VARCHAR(500) NULL,
  ADD COLUMN series_id VARCHAR(36) NULL,
  ADD COLUMN occurrence_date DATE NULL;

CREATE INDEX idx_schedule_series_id
  ON schedule(series_id, occurrence_date);

CREATE UNIQUE INDEX uk_schedule_series_occurrence
  ON schedule(series_id, occurrence_date);

CREATE TABLE schedule_series_exdate (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  series_id VARCHAR(36) NOT NULL,
  excluded_date DATE NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_series_exdate (series_id, excluded_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;