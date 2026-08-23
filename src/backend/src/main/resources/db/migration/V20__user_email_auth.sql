ALTER TABLE `user`
  MODIFY COLUMN phone VARCHAR(20) NULL,
  ADD COLUMN email VARCHAR(254) NULL AFTER phone,
  ADD COLUMN email_verified_at DATETIME NULL AFTER email,
  ADD UNIQUE KEY uk_user_email (email);

CREATE TABLE IF NOT EXISTS auth_email_otp (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NULL,
  email VARCHAR(254) NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  code_digest VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'issued',
  attempt_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts TINYINT UNSIGNED NOT NULL DEFAULT 5,
  sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL,
  consumed_at DATETIME NULL,
  invalidated_at DATETIME NULL,
  request_ip VARCHAR(45) NULL,
  user_agent TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_auth_email_otp_lookup (email, purpose, status, expires_at),
  KEY idx_auth_email_otp_user_status (user_id, status),
  KEY idx_auth_email_otp_expires_at (expires_at),
  KEY idx_auth_email_otp_email_sent_at (email, sent_at),
  KEY idx_auth_email_otp_ip_sent_at (request_ip, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
