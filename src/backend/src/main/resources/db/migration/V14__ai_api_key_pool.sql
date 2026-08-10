CREATE TABLE IF NOT EXISTS ai_api_key (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  api_key_masked VARCHAR(100) NOT NULL,
  api_key_ciphertext TEXT NOT NULL,
  priority INT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  remark TEXT NULL,
  last_test_status VARCHAR(20) NULL,
  last_tested_at DATETIME NULL,
  last_error VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ai_api_key_order (priority, id),
  KEY idx_ai_api_key_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_key_pool_state (
  id TINYINT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ai_key_pool_state (id, revision)
VALUES (1, 0)
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO ai_api_key (name, api_key_masked, api_key_ciphertext, priority, enabled, remark)
SELECT 'Primary key', COALESCE(NULLIF(TRIM(c.api_key_masked), ''), '****'), c.api_key_ciphertext, 1, TRUE, 'Migrated from the previous AI configuration'
FROM ai_config c
WHERE c.id = (SELECT latest.id FROM (SELECT MAX(id) id FROM ai_config) latest)
  AND c.api_key_ciphertext IS NOT NULL
  AND c.api_key_ciphertext <> ''
  AND NOT EXISTS (SELECT 1 FROM ai_api_key);

UPDATE ai_key_pool_state
SET revision = 1
WHERE id = 1
  AND revision = 0
  AND EXISTS (SELECT 1 FROM ai_api_key);
