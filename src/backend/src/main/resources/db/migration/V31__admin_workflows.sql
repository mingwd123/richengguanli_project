ALTER TABLE security_event ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'pending';
ALTER TABLE security_event ADD COLUMN review_note VARCHAR(1000) NULL;
ALTER TABLE security_event ADD COLUMN reviewed_by BIGINT NULL;
ALTER TABLE security_event ADD COLUMN reviewed_at DATETIME NULL;
ALTER TABLE security_event ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_security_review ON security_event (review_status, created_at);

ALTER TABLE ai_quota_setting ADD COLUMN warning_percent INT NOT NULL DEFAULT 80;
CREATE TABLE ai_quota_override (
  scope_type VARCHAR(12) NOT NULL,
  subject_id BIGINT NOT NULL,
  daily_limit INT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (scope_type, subject_id)
);
ALTER TABLE ai_quota_alert ADD COLUMN alert_kind VARCHAR(20) NOT NULL DEFAULT 'exceeded';
ALTER TABLE ai_quota_alert ADD COLUMN used_calls INT NULL;
ALTER TABLE ai_quota_alert ADD COLUMN usage_date DATE NULL;
CREATE INDEX idx_quota_alert_scope ON ai_quota_alert (scope_type, subject_id, usage_date, alert_kind);

ALTER TABLE ai_usage_log ADD COLUMN team_id BIGINT NULL;
ALTER TABLE ai_usage_log ADD COLUMN failure_kind VARCHAR(30) NULL;
ALTER TABLE ai_usage_log ADD COLUMN latency_ms BIGINT NULL;
