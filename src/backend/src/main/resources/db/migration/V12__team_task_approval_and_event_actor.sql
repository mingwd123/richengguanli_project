ALTER TABLE team_task
  ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'approved' AFTER status,
  ADD COLUMN reviewed_by BIGINT NULL AFTER approval_status,
  ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by,
  ADD COLUMN unassigned_count INT NOT NULL DEFAULT 0 AFTER reviewed_at;

ALTER TABLE team_task_event
  ADD COLUMN actor_type VARCHAR(20) NOT NULL DEFAULT 'user' AFTER actor_id;

UPDATE team_task_event e
JOIN admin_operation_log l
  ON l.admin_id = e.actor_id
 AND l.target_type = 'team_task'
 AND l.target_id = e.task_id
 AND l.action = CASE e.event_type
   WHEN 'cancelled' THEN 'cancel_team_task'
   WHEN 'restored' THEN 'restore_team_task'
   WHEN 'status_corrected' THEN 'correct_assignee_status'
 END
 AND e.content LIKE '管理员%'
 AND ABS(TIMESTAMPDIFF(SECOND, e.created_at, l.created_at)) <= 5
SET e.actor_type = 'admin'
WHERE e.event_type IN ('cancelled', 'restored', 'status_corrected');

-- Normalize legacy assignments that still reference a removed/non-member user.
UPDATE team_task_assignee a
JOIN team_task t ON t.id = a.task_id
LEFT JOIN team_member m
  ON m.team_id = t.team_id
 AND m.user_id = a.user_id
 AND m.status = 'active'
SET a.is_active = FALSE
WHERE a.is_active = TRUE
  AND a.status <> 'completed'
  AND t.deleted_at IS NULL
  AND t.status <> 'completed'
  AND m.id IS NULL;

-- Keep the newest active row before adding the one-active-assignee constraint.
UPDATE team_task_assignee older
JOIN team_task_assignee newer
  ON newer.task_id = older.task_id
 AND newer.user_id = older.user_id
 AND newer.is_active = TRUE
 AND newer.id > older.id
SET older.is_active = FALSE
WHERE older.is_active = TRUE;

UPDATE team_task t
SET unassigned_count = (
  SELECT COUNT(*)
  FROM team_task_assignee a
  WHERE a.task_id = t.id
    AND a.is_active = FALSE
    AND a.status IN ('pending', 'accepted')
    AND NOT EXISTS (
      SELECT 1
      FROM team_task_assignee replacement
      WHERE replacement.task_id = a.task_id
        AND (
          (replacement.reassigned_from_user_id = a.user_id
            AND replacement.assign_round > a.assign_round)
          OR (replacement.user_id = a.user_id AND replacement.is_active = TRUE)
        )
    )
)
WHERE t.deleted_at IS NULL
  AND t.status <> 'completed';

UPDATE team_task
SET status = 'unassigned'
WHERE approval_status = 'approved'
  AND unassigned_count > 0
  AND status IN ('active', 'all_rejected');

ALTER TABLE team_task_assignee
  ADD COLUMN active_user_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN is_active THEN user_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_task_active_user (task_id, active_user_id);

CREATE TABLE IF NOT EXISTS team_task_reminder_plan (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  remind_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_task_reminder_plan (task_id, remind_at),
  KEY idx_team_task_reminder_plan_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO team_task_reminder_plan (task_id, remind_at)
SELECT target_id, remind_at
FROM reminder
WHERE target_type = 'team_task'
  AND status IN ('pending', 'paused')
GROUP BY target_id, remind_at;
