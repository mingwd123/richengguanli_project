-- Dayliane database schema initialization.
-- MySQL 8.0+, application-level logical foreign keys, UTC datetime storage.

CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phone VARCHAR(20) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(50) NOT NULL,
  avatar_url VARCHAR(500) NULL,
  timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Shanghai',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  deleted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_phone (phone),
  KEY idx_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS team (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  invite_code VARCHAR(10) NOT NULL,
  invite_code_expire_at DATETIME NOT NULL,
  owner_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  deleted_at DATETIME NULL,
  deleted_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_invite_code (invite_code),
  KEY idx_team_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS team_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  team_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'member',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  removed_at DATETIME NULL,
  removed_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_member (team_id, user_id),
  KEY idx_team_member_user_id (user_id),
  KEY idx_team_member_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS schedule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NULL,
  group_name VARCHAR(50) NOT NULL DEFAULT 'Default',
  group_id BIGINT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  time_type VARCHAR(30) NOT NULL,
  start_time DATETIME NULL,
  end_time DATETIME NULL,
  deadline_time DATETIME NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  deleted_at DATETIME NULL,
  deleted_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_schedule_user_id (user_id),
  KEY idx_schedule_status (status),
  KEY idx_schedule_deadline_time (deadline_time),
  KEY idx_schedule_user_id_created_at (user_id, created_at DESC),
  KEY idx_schedule_group_id (group_id),
  KEY idx_schedule_sort_order (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reminder (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  target_type VARCHAR(30) NOT NULL,
  target_id BIGINT NOT NULL,
  remind_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  sent_at DATETIME NULL,
  error_message TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_reminder_status_remind_at (user_id, status, remind_at),
  KEY idx_reminder_target (target_type, target_id),
  KEY idx_reminder_user_id_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS team_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  team_id BIGINT NOT NULL,
  creator_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NULL,
  group_name VARCHAR(50) NULL,
  group_id BIGINT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  start_time DATETIME NULL,
  deadline_time DATETIME NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'active',
  updated_by BIGINT NULL,
  time_updated_at DATETIME NULL,
  deleted_at DATETIME NULL,
  deleted_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_team_task_team_id (team_id),
  KEY idx_team_task_creator_id (creator_id),
  KEY idx_team_task_status (status),
  KEY idx_team_task_deadline_time (deadline_time),
  KEY idx_team_task_start_time (start_time),
  KEY idx_team_task_group_id (group_id),
  KEY idx_team_task_sort_order (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS team_task_assignee (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  assign_round INT NOT NULL DEFAULT 1,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  accepted_at DATETIME NULL,
  rejected_at DATETIME NULL,
  completed_at DATETIME NULL,
  reassigned_from_user_id BIGINT NULL,
  assigned_by BIGINT NULL,
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status_updated_by BIGINT NULL,
  status_updated_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_user_round (task_id, user_id, assign_round),
  KEY idx_assignee_task_id (task_id),
  KEY idx_assignee_user_id (user_id),
  KEY idx_assignee_is_active (task_id, is_active),
  KEY idx_assignee_user_active (user_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  type VARCHAR(50) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content TEXT NULL,
  related_type VARCHAR(30) NULL,
  related_id BIGINT NULL,
  reminder_id BIGINT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  read_at DATETIME NULL,
  deleted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_notification_user_read (user_id, is_read, created_at DESC),
  KEY idx_notification_user_id (user_id),
  KEY idx_notification_related (related_type, related_id),
  KEY idx_notification_reminder_id (reminder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS admin_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'admin',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  last_login_at DATETIME NULL,
  last_login_ip VARCHAR(45) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS admin_operation_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  admin_id BIGINT NOT NULL,
  action VARCHAR(50) NOT NULL,
  target_type VARCHAR(30) NULL,
  target_id BIGINT NULL,
  before_data JSON NULL,
  after_data JSON NULL,
  ip_address VARCHAR(45) NULL,
  user_agent TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_admin_log_admin_id (admin_id),
  KEY idx_admin_log_target (target_type, target_id),
  KEY idx_admin_log_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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

CREATE TABLE IF NOT EXISTS ai_usage_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NULL,
  feature_type VARCHAR(30) NOT NULL,
  input_text TEXT NULL,
  output_text TEXT NULL,
  status VARCHAR(20) NOT NULL,
  error_message TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ai_usage_user_id (user_id),
  KEY idx_ai_usage_feature_type (feature_type),
  KEY idx_ai_usage_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_config (
  id BIGINT NOT NULL AUTO_INCREMENT,
  provider VARCHAR(30) NOT NULL,
  model_name VARCHAR(50) NOT NULL,
  api_base_url VARCHAR(500) NULL,
  api_key_masked VARCHAR(100) NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  remark TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ai_config_enabled (enabled),
  KEY idx_ai_config_provider (provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;