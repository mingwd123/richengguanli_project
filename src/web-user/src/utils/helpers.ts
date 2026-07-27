import type { ScheduleForm, TimelineItem, CalendarDay } from '../types'

export function toSchedulePayload(input: ScheduleForm) {
  const out: Record<string, any> = { title: input.title, groupId: Number(input.groupId) || null, groupName: input.groupName, timeType: input.timeType, startTime: '', endTime: '', deadlineTime: '' }
  if (input.timeType === 'point_event') out.startTime = toIso(input.startTime)
  if (input.timeType === 'deadline_task') out.deadlineTime = toIso(input.deadlineTime)
  if (input.timeType === 'duration_task') {
    out.startTime = toIso(input.startTime)
    out.endTime = toIso(input.endTime)
  }
  return out
}

export function toIso(value: string) { return value ? new Date(value).toISOString() : '' }

export function toApiTimePayload(input: Record<string, any>) {
  const out = { ...input }
  for (const key of ['startTime', 'endTime', 'deadlineTime']) if (out[key]) out[key] = new Date(out[key]).toISOString()
  return out
}

export function normalizeTimelineItem(item: any, sourceType: 'schedule' | 'team_task'): TimelineItem {
  const startAt = item.startTime || ''
  const endAt = item.endTime || ''
  const deadlineAt = item.deadlineTime || ''
  const kind = sourceType === 'team_task'
    ? (startAt && deadlineAt ? 'duration_task' : 'deadline_task')
    : item.timeType === 'point_event' ? 'point_event' : item.timeType === 'duration_task' ? 'duration_task' : 'deadline_task'
  const sortAt = kind === 'point_event' ? startAt : kind === 'duration_task' ? (endAt || deadlineAt || startAt) : (deadlineAt || startAt)
  return { ...item, sourceType, kind, kindLabel: kindName(kind), sortAt, statusText: item.assignStatus || item.status, sourceLabel: sourceType === 'team_task' ? (item.teamName || '团队任务') : (item.groupName || '个人日程') }
}

function kindName(kind: string) { return kind === 'point_event' ? '安排事项' : kind === 'duration_task' ? '时间段任务' : '待办任务' }

export function buildMonthDays(items: any[]): CalendarDay[] {
  const now = new Date(); const first = new Date(now.getFullYear(), now.getMonth(), 1); const days: CalendarDay[] = []
  for (let i = 0; i < (first.getDay() + 6) % 7; i++) days.push({ day: '', today: false, items: [] })
  const count = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()
  for (let d = 1; d <= count; d++) {
    const key = new Date(now.getFullYear(), now.getMonth(), d).toISOString().slice(0, 10)
    days.push({ day: d, today: d === now.getDate(), items: items.filter(item => primaryTime(item).startsWith(key)) })
  }
  return days
}

export function primaryTime(item: any) { return item.deadlineTime || item.endTime || item.startTime || item.createdAt || '' }

export function formatTime(value: string) {
  if (!value) return '未设置'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

export function countdown(value: string) {
  if (!value) return '无截止时间'
  const diff = new Date(value).getTime() - Date.now()
  const abs = Math.abs(diff)
  const minutes = Math.floor(abs / 60000)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)
  if (diff < 0) return days > 0 ? `已逾期 ${days} 天` : hours > 0 ? `已逾期 ${hours} 小时` : `已逾期 ${minutes} 分钟`
  if (hours >= 24) return `剩余 ${days} 天`
  return `剩余 ${hours} 小时 ${minutes % 60} 分钟`
}

export function urgency(value: string) {
  if (!value) return ''
  const diff = new Date(value).getTime() - Date.now()
  if (diff < 0 || diff <= 10 * 60000) return 'danger'
  if (diff <= 30 * 60000) return 'warning'
  return ''
}

export function isOverdue(item: any) {
  const at = primaryTime(item)
  return !!at && !['completed', 'cancelled'].includes(item.status) && item.assignStatus !== 'completed' && new Date(at).getTime() < Date.now()
}

export function sortByPriority<T extends Record<string, any>>(items: T[]) {
  return [...items].sort((a, b) => {
    const overdueDiff = Number(isOverdue(b)) - Number(isOverdue(a))
    if (overdueDiff) return overdueDiff
    return new Date(primaryTime(a)).getTime() - new Date(primaryTime(b)).getTime()
  })
}

export function groupByTaskState<T extends Record<string, any>>(items: T[], groupName: (item: T) => string) {
  const groups = new Map<string, T[]>()
  for (const item of sortByPriority(items)) {
    const name = ['completed', 'cancelled'].includes(item.status) || item.assignStatus === 'completed' ? '已完成' : (groupName(item) || '未分组')
    groups.set(name, [...(groups.get(name) || []), item])
  }
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
}

export function timeTypeLabel(t: string) {
  return t === 'point_event' ? '安排事项' : t === 'duration_task' ? '时间段任务' : '待办任务'
}

export function statusLabel(s: string) {
  const map: Record<string, string> = {
    pending: '待办', completed: '已完成', cancelled: '已取消',
    active: '进行中', all_rejected: '全部拒绝',
    accepted: '已接受', rejected: '已拒绝'
  }
  return map[s] || s
}
