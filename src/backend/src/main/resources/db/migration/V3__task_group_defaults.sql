-- Ensure personal task groups are available for existing databases and demo users.

CREATE TABLE IF NOT EXISTS task_group (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NULL,
  team_id BIGINT NULL,
  scope VARCHAR(20) NOT NULL,
  name VARCHAR(50) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task_group_user_id (user_id),
  KEY idx_task_group_team_id (team_id),
  KEY idx_task_group_scope (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO task_group (user_id, scope, name, sort_order, is_default)
SELECT u.id, 'personal', '工作', 10, TRUE
FROM `user` u
WHERE u.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM task_group g
    WHERE g.user_id = u.id AND g.scope = 'personal' AND g.name = '工作' AND g.deleted_at IS NULL
  );

INSERT INTO task_group (user_id, scope, name, sort_order, is_default)
SELECT u.id, 'personal', '生活', 20, FALSE
FROM `user` u
WHERE u.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM task_group g
    WHERE g.user_id = u.id AND g.scope = 'personal' AND g.name = '生活' AND g.deleted_at IS NULL
  );

INSERT INTO task_group (user_id, scope, name, sort_order, is_default)
SELECT u.id, 'personal', '团队', 30, FALSE
FROM `user` u
WHERE u.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM task_group g
    WHERE g.user_id = u.id AND g.scope = 'personal' AND g.name = '团队' AND g.deleted_at IS NULL
  );
