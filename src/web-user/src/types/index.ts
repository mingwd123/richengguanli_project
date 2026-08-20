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
export type ScheduleLevel = 1 | 2 | 3 | 4 | 5
export type ScheduleViewMode = 'time' | 'group' | 'urgency' | 'fatigue'

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
  urgencyLevel: ScheduleLevel
  urgencyLabel: string
  fatigueLevel: ScheduleLevel
  fatigueLabel: string
  fatigueWeight: number
  completedAt: string
  completedFatigueLevel: ScheduleLevel | null
  completedFatigueWeight: number | null
  sectionKey?: string
  sectionLabel?: string
  isOverdue?: boolean
  timeStatus?: string
  effectiveTime?: string
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
  fatigueAlertEnabled: boolean
  fatigueSurveyEnabled: boolean
  quietStartTime: string
  quietEndTime: string
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
  | 'fatigue_plan_warning'
  | 'fatigue_actual_warning'
  | 'fatigue_survey'

export interface Notification {
  id: number
  userId: number
  type: NotificationType
  title: string
  content: string
  relatedType: string | null
  relatedId: number | null
  reminderId: number | null
  localDate: string
  targetRoute: string
  dataRevision: number | null
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
  fatigue?: FatigueDailySummary
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
  urgencyLevel: ScheduleLevel
  fatigueLevel: ScheduleLevel
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

export interface SectionSummary {
  key: string
  label: string
  total: number
  pendingCount: number
  completedCount: number
  plannedLoad: number
  completedLoad: number
}

export interface ScheduleListResult extends PageResult<Schedule> {
  viewMode: ScheduleViewMode
  sectionSummaries: SectionSummary[]
  statusCounts?: Partial<Record<ScheduleStatus, number>>
  dataRevision: number
  revision: string
}

export interface FatigueProfile {
  userId: number
  level1Weight: number
  level2Weight: number
  level3Weight: number
  level4Weight: number
  level5Weight: number
  capacity75: number
  modelStage: 'default' | 'calibrating' | 'personalized' | string
  validSurveyDays: number
  algorithmVersion: number
  fatigueTrackingEnabled: boolean
  fatigueAlertEnabled: boolean
  surveyEnabled: boolean
  surveyTime: string
  capacityLocked: boolean
  dataRevision: number
  lastCalibratedAt: string
  lastWeightCalibratedAt: string
  surveySnoozedUntil: string
  surveySkippedDate: string
  alertSuppressedDate: string
  alertSuppressedBand: string
  weights: Record<string, number>
  confidence: 'low' | 'medium' | 'high' | string
  featureEnabled: boolean
  alertsFeatureEnabled: boolean
  surveysFeatureEnabled: boolean
  learningFeatureEnabled: boolean
  retentionDays: number
  disclaimer: string
}

export interface FatigueDailySummary {
  localDate: string
  timezone: string
  plannedLoad: number
  completedLoad: number
  predictedScore: number
  actualLoadScore: number
  pendingCount: number
  completedCount: number
  capacity75: number
  level: string
  modelStage: string
  confidence: string
  algorithmVersion?: number
  dataRevision?: number
  trackingEnabled?: boolean
  featureEnabled?: boolean
  completedLevelCounts?: Record<string, number>
  topContributors?: Array<{ scheduleId: number; title: string; urgencyLevel?: number; fatigueLevel: number; weight: number }>
  survey?: FatigueSurvey | Record<string, never>
  disclaimer?: string
}

export interface FatigueSurvey {
  localDate: string
  timezone: string
  score: number
  externalFactorLevel: number
  externalFactorTags: string
  externalFactorTagList?: string[]
  completedLoadSnapshot: number
  completedLevelCounts?: Record<string, number>
  capacityBefore: number
  capacityAfter: number | null
  modelEligible: boolean
  ineligibleReason: string | null
  learningWeight: number
  algorithmVersion: number
  submittedAt: string
  updatedAt: string
}

export interface FatigueSurveyComparison {
  predictedScore: number
  feedbackScore: number
  difference: number
  modelStage: string
  capacityBefore: number
  capacityAfter: number
  modelEligible: boolean
  learningWeight: number
  adjustmentText: string
}

export interface FatigueSurveyToday {
  localDate: string
  timezone: string
  pending: boolean
  isBackfill: boolean
  availableDates: string[]
  canSnooze: boolean
  canSkip: boolean
  survey: FatigueSurvey | Record<string, never>
  daily: FatigueDailySummary
  profile: FatigueProfile
  comparison?: FatigueSurveyComparison | Record<string, never>
  recurringExternalFactors?: Array<{ tag: string; days: number }>
  disclaimer?: string
}

export interface FatigueHistoryItem {
  localDate: string
  timezone: string
  plannedLoad: number
  completedLoad: number
  predictedScore: number
  actualScore?: number
  pendingCount: number
  completedCount: number
  modelEligible?: boolean
  ineligibleReason?: string | null
  learningWeight?: number
  algorithmVersion: number
}

export interface FatigueHistory {
  dateFrom: string
  dateTo: string
  timezone: string
  profile: FatigueProfile
  list: FatigueHistoryItem[]
  disclaimer: string
}

export interface FatigueProjection {
  plannedLoad: number
  capacity75: number
  predictedScore: number
  level: string
}

export interface FatiguePreviewDate {
  localDate: string
  before: FatigueProjection
  after: FatigueProjection
  removedWeight: number
  addedWeight: number
  deltaScore: number
  shouldWarn: boolean
  warning: string
  topContributors: Array<{ scheduleId: number; title: string; urgencyLevel?: number; fatigueLevel: number; weight: number }>
}

export interface FatiguePreview {
  localDate: string
  before: FatigueProjection
  after: FatigueProjection
  removedWeight: number
  affectedDates: string[]
  dates: FatiguePreviewDate[]
  shouldWarn: boolean
  disclaimer: string
}
