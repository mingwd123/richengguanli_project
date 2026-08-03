CREATE TABLE IF NOT EXISTS reminder_preset (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  offset_minutes INT NOT NULL,
  sort_order INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_reminder_preset_user_offset (user_id, offset_minutes),
  KEY idx_reminder_preset_user_order (user_id, sort_order)
);
