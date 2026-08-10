/* ========== 用户 ========== */
export interface UserProfile {
  id: number
  phone: string
  nickname: string
  avatarUrl: string
  timezone: string
  createdAt: string
}

/* ========== 日程 ========== */
export type TimeType = 'point_event' | 'deadline_task' | 'duration_task'
export type ScheduleStatus = 'pending' | 'completed' | 'cancelled'

export interface Schedule {
  id: number
  title: string
  description: string
  groupId: number | null
  groupName: string
  sortOrder: number
  timeType: TimeType
  startTime: string
  endTime: string
  deadlineTime: string
  status: ScheduleStatus
  reminderCount: number
  pendingReminders: Reminder[]
  remindAt?: string
  createdAt: string
  updatedAt: string
}

/* ========== 任务分组（模块） ========== */
export interface TaskGroup {
  id: number
  name: string
  scope: string
  sortOrder: number
  teamId: number | null
  isDefault: boolean
}

/* ========== 团队 ========== */
export type TeamRole = 'owner' | 'admin' | 'member'
export type MemberStatus = 'active' | 'inactive' | 'removed'

export interface Team {
  id: number
  name: string
  inviteCode: string
  role?: TeamRole
  myRole: TeamRole
  memberCount: number
  activeTaskCount: number
  createdAt: string
}

export interface TeamMember {
  id: number
  userId: number
  nickname: string
  avatarUrl?: string
  phone: string
  role: TeamRole
  status: MemberStatus
  joinedAt: string
}

/* ========== 团队任务 ========== */
export type TeamTaskStatus = 'pending_approval' | 'active' | 'unassigned' | 'completed' | 'cancelled' | 'all_rejected' | 'approval_rejected'
export type AssignStatus = 'pending' | 'accepted' | 'rejected' | 'completed'
export type ApprovalStatus = 'pending' | 'approved' | 'rejected'

export interface TeamTaskAssignee {
  assigneeId: number
  userId: number
  nickname: string
  avatarUrl?: string
  assignStatus: AssignStatus
  assignRound: number
  isCurrent: boolean
}

export interface TeamTaskReassignmentCandidate {
  assigneeId: number
  userId: number
  nickname: string
  status: AssignStatus
}

export interface TeamTaskEvent {
  id: number
  eventType: string
  content: string
  actorName: string
  createdAt: string
}

export interface TeamTask {
  id: number
  title: string
  description: string
  teamId: number
  teamName: string
  groupId: number | null
  groupName: string
  sortOrder: number
  creatorId: number
  creatorName: string
  canManage: boolean
  deadlineTime: string
  startTime: string
  endTime: string
  status: TeamTaskStatus
  approvalStatus?: ApprovalStatus
  reviewedBy?: number | null
  reviewedAt?: string
  unassignedCount?: number
  canReview?: boolean
  assignees: TeamTaskAssignee[]
  reassignmentCandidates?: TeamTaskReassignmentCandidate[]
  events: TeamTaskEvent[]
  pendingReminders: Reminder[]
  createdAt: string
  updatedAt: string
}

/* 我的任务视图（简化版） */
export interface MyTask {
  id: number
  title: string
  teamId: number
  teamName: string
  groupId: number | null
  groupName: string
  sortOrder: number
  creatorId: number
  deadlineTime: string
  startTime: string
  status: TeamTaskStatus
  approvalStatus?: ApprovalStatus
  canReview?: boolean
  description?: string
  assignStatus?: AssignStatus
  assignees?: TeamTaskAssignee[]
  reassignmentCandidates?: TeamTaskReassignmentCandidate[]
  assigneeCount?: number
  remindAt?: string
  createdAt: string
  assigneeId?: number
  assignRound?: number
}

export interface NotificationPreference {
  browserEnabled: boolean
  taskAssignedEnabled: boolean
  taskStatusEnabled: boolean
  reminderEnabled: boolean
  reminderPresetMinutes: number[]
}

/* ========== 通知 ========== */
export type NotificationType =
  | 'schedule_reminder'
  | 'team_task_assigned'
  | 'team_task_status'
  | 'team_member_joined'
  | 'task_approval_requested'
  | 'task_approved'
  | 'task_approval_rejected'
  | 'task_unassigned'

export interface Notification {
  id: number
  userId: number
  type: NotificationType
  title: string
  content: string
  relatedType: string | null
  relatedId: number | null
  reminderId: number | null
  isRead: boolean
  createdAt: string
}

export type ReminderStatus = 'pending' | 'paused' | 'sent' | 'cancelled' | 'failed'

export interface Reminder {
  id: number
  userId: number
  targetType: 'schedule' | 'team_task'
  targetId: number
  targetTitle: string
  remindAt: string
  status: ReminderStatus
  sentAt: string
  errorMessage: string
  createdAt: string
}

/* ========== 首页概览 ========== */
export interface TodayOverview {
  date?: string
  timezone?: string
  personalSchedules: Schedule[]
  teamTasks: MyTask[]
  unreadNotificationCount: number
  groups: { name: string; items: number }[]
}

export interface UpcomingOverview {
  timezone: string
  dateFrom: string
  dateTo: string
  personalSchedules: Schedule[]
  teamTasks: MyTask[]
  list: Array<(Schedule | MyTask) & { sourceType: 'schedule' | 'team_task' }>
}

/* ========== 日历 ========== */
export interface CalendarDay {
  day: number | ''
  today: boolean
  items: any[]
}

/* ========== 时间轴 ========== */
export interface TimelineItem {
  id: number
  title: string
  sourceType: 'schedule' | 'team_task'
  kind: TimeType
  kindLabel: string
  sortAt: string
  status: string
  statusText?: string
  assignStatus?: string
  sourceLabel: string
  teamName?: string
  groupName?: string
  deadlineTime?: string
  startTime?: string
  endTime?: string
}

export type TimelinePresentationStatus =
  | 'upcoming'
  | 'active'
  | 'past'
  | 'overdue'
  | 'completed'
  | 'cancelled'
  | 'rejected'
  | 'unscheduled'

export interface TimelineTimeRange {
  kind: TimeType
  startAt: string
  endAt: string
  sortAt: string
  isDuration: boolean
}

export interface TimelineStats {
  today: number
  overdue: number
  upcoming: number
  active: number
  past: number
}

/* ========== 表单 ========== */
export interface LoginForm {
  phone: string
  password: string
}

export interface RegisterForm {
  phone: string
  password: string
  confirmPassword: string
  nickname: string
}

export interface ScheduleForm {
  title: string
  description: string
  groupId: string
  groupName: string
  timeType: TimeType
  startTime: string
  endTime: string
  deadlineTime: string
  remindAt: string
}

export interface GroupForm {
  name: string
}

export interface TeamForm {
  name: string
}

export interface TaskForm {
  teamId: string
  groupId: string
  title: string
  description: string
  deadlineTime: string
  startTime: string
  remindAt: string
  assigneeUserIds: number[]
}

/* ========== API 响应 ========== */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}
