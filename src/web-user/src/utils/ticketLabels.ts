// 工单模块类型与文案（计划 §三/§四）。
// 状态、类型、模块的存储键与服务端约定一致；文案集中在这里，网页端与桌面端共用。

export interface TicketSettings {
  enabled: boolean
  version: number
  updatedAt: string
}

export interface TicketAttachmentView {
  id: number
  mimeType: string
  width: number | null
  height: number | null
  name: string | null
}

export interface TicketViewerState {
  isAuthor: boolean
  following: boolean
  reacted: boolean
}

export interface TicketListItem {
  id: number
  ticketNo: string
  title: string
  category: TicketCategory
  module: TicketModule
  status: TicketStatus
  pinned: boolean
  closeReason: string | null
  duplicateOfId: number | null
  authorName: string
  replyCount: number
  reactionCount: number
  createdAt: string
  lastActivityAt: string
  viewer: TicketViewerState | null
}

export interface TicketDetail extends TicketListItem {
  description: string
  steps: string | null
  expectedResult: string | null
  environment: Record<string, string>
  pagePath: string | null
  resolution: string | null
  fixedVersion: string | null
  closeNote: string | null
  mergedAt: string | null
  duplicateOf: { id: number; ticketNo: string; title: string } | null
  followerCount: number
  updatedAt: string
  attachments: TicketAttachmentView[]
  priority?: string
  assigneeName?: string
  hiddenAt?: string | null
  version?: number
}

export interface TicketMessage {
  id: number
  actorType: 'user' | 'admin'
  actorId: number
  actorName: string
  isAdmin: boolean
  visibility?: string
  content: string
  createdAt: string
  hiddenAt?: string | null
  attachments: TicketAttachmentView[]
}

export interface TicketEventItem {
  action: string
  fromStatus: string | null
  toStatus: string | null
  note: string | null
  actorType: string
  actorName: string
  createdAt: string
  visibility?: string
}

export interface TicketListResult {
  items: TicketListItem[]
  total: number
  page: number
  size: number
  enabled?: boolean
  counts?: { openCount: number | null; inProgressCount: number | null; waitingCount: number | null }
}

export type TicketStatus = 'open' | 'in_progress' | 'waiting_reporter' | 'resolved' | 'closed'
export type TicketCategory = 'bug' | 'display' | 'data' | 'account' | 'question' | 'suggestion' | 'other'
export type TicketModule = 'schedule' | 'team_task' | 'daily_progress' | 'fatigue' | 'notification' | 'ai' | 'account' | 'desktop' | 'other'
export type TicketPriority = 'low' | 'normal' | 'high' | 'urgent'

export const TICKET_STATUS_LABELS: Record<TicketStatus, string> = {
  open: '待处理',
  in_progress: '处理中',
  waiting_reporter: '待补充',
  resolved: '已解决',
  closed: '已关闭',
}

export const TICKET_CATEGORY_LABELS: Record<TicketCategory, string> = {
  bug: '功能异常',
  display: '显示问题',
  data: '数据问题',
  account: '账号问题',
  question: '使用咨询',
  suggestion: '功能建议',
  other: '其他',
}

export const TICKET_MODULE_LABELS: Record<TicketModule, string> = {
  schedule: '日程',
  team_task: '团队任务',
  daily_progress: '每日进度',
  fatigue: '疲劳评估',
  notification: '通知提醒',
  ai: 'AI',
  account: '账号与设置',
  desktop: '桌面端',
  other: '其他',
}

export const TICKET_PRIORITY_LABELS: Record<TicketPriority, string> = {
  low: '低',
  normal: '普通',
  high: '高',
  urgent: '紧急',
}

export const TICKET_CLOSE_REASON_LABELS: Record<string, string> = {
  resolved: '已解决',
  duplicate: '重复问题',
  user_withdrawn: '用户撤回',
  insufficient_info: '信息不足',
  not_supported: '暂不支持',
  violation: '违规或无效内容',
  other: '其他',
}

export function ticketStatusLabel(status: string) {
  return TICKET_STATUS_LABELS[status as TicketStatus] ?? status
}

export function ticketCategoryLabel(category: string) {
  return TICKET_CATEGORY_LABELS[category as TicketCategory] ?? category
}

export function ticketModuleLabel(module: string) {
  return TICKET_MODULE_LABELS[module as TicketModule] ?? module
}

export function ticketPriorityLabel(priority: string) {
  return TICKET_PRIORITY_LABELS[priority as TicketPriority] ?? priority
}

export function ticketCloseReasonLabel(reason: string | null | undefined) {
  if (!reason) return ''
  return TICKET_CLOSE_REASON_LABELS[reason] ?? reason
}
