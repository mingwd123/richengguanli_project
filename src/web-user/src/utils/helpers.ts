import type { ScheduleForm, TimelineItem, CalendarDay, TimeType } from '../types'
import { isTimelineOverdue, timelineKind, timelineOccursOnDate, timelineTimeRange } from './timeline'

let displayTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'

export function setDisplayTimezone(timezone: string) {
  try {
    new Intl.DateTimeFormat('zh-CN', { timeZone: timezone }).format()
    displayTimezone = timezone
  } catch {
    displayTimezone = 'Asia/Shanghai'
  }
}

export function getDisplayTimezone() { return displayTimezone }

function dateTimePartsInTimezone(value: number | string | Date, timezone: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date)
  const part = (type: string) => Number(parts.find(item => item.type === type)?.value || 0)
  return {
    year: part('year'),
    month: part('month'),
    day: part('day'),
    hour: part('hour'),
    minute: part('minute'),
    second: part('second')
  }
}

export function toDatetimeLocalInTimezone(value: string, timezone = displayTimezone) {
  if (!value) return ''
  const text = String(value).trim().replace(' ', 'T')
  if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(text)) return text.slice(0, 16)
  const parts = dateTimePartsInTimezone(text, timezone)
  if (!parts) return String(value).trim().replace(' ', 'T').slice(0, 16)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}T${pad(parts.hour)}:${pad(parts.minute)}`
}

function timezoneOffsetAt(timestamp: number, timezone: string) {
  const parts = dateTimePartsInTimezone(timestamp, timezone)
  if (!parts) return 0
  const zonedAsUtc = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second)
  return zonedAsUtc - Math.floor(timestamp / 1000) * 1000
}

export function zonedDateTimeToIso(value: string, timezone = displayTimezone) {
  if (!value) return ''
  const text = String(value).trim().replace(' ', 'T')
  if (/[zZ]|[+-]\d{2}:?\d{2}$/.test(text)) return new Date(text).toISOString()

  const match = text.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/)
  if (!match) return new Date(text).toISOString()
  const [, year, month, day, hour, minute, second = '0'] = match
  const desiredAsUtc = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second))
  let timestamp = desiredAsUtc
  for (let attempt = 0; attempt < 3; attempt++) {
    const adjusted = desiredAsUtc - timezoneOffsetAt(timestamp, timezone)
    if (adjusted === timestamp) break
    timestamp = adjusted
  }

  const localValue = `${year}-${month}-${day}T${hour}:${minute}`
  if (toDatetimeLocalInTimezone(new Date(timestamp).toISOString(), timezone) !== localValue) {
    throw new RangeError('该时间在当前用户时区不存在')
  }
  return new Date(timestamp).toISOString()
}

export function dateKeyInTimezone(value: number | string | Date = new Date(), timezone = displayTimezone) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date)
  const part = (type: string) => parts.find(item => item.type === type)?.value || ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function currentDateParts(timezone = displayTimezone) {
  const [year, month, day] = dateKeyInTimezone(new Date(), timezone).split('-').map(Number)
  return { year, month, day }
}

export function toSchedulePayload(input: ScheduleForm, timezone = displayTimezone) {
  const out: Record<string, any> = { title: input.title, description: input.description || '', groupId: Number(input.groupId) || null, groupName: input.groupName, timeType: input.timeType, startTime: '', endTime: '', deadlineTime: '' }
  if (input.timeType === 'point_event') out.startTime = toIso(input.startTime, timezone)
  if (input.timeType === 'deadline_task') out.deadlineTime = toIso(input.deadlineTime, timezone)
  if (input.timeType === 'duration_task') {
    out.startTime = toIso(input.startTime, timezone)
    out.endTime = toIso(input.endTime, timezone)
  }
  if (input.remindAt) out.remindAt = toIso(input.remindAt, timezone)
  return out
}

export function toIso(value: string, timezone = displayTimezone) { return zonedDateTimeToIso(value, timezone) }

export function reminderTimeForOffset(baseTime: string, offsetMinutes: number, timezone = displayTimezone) {
  if (!baseTime || !Number.isInteger(offsetMinutes) || offsetMinutes <= 0) return ''
  const base = new Date(zonedDateTimeToIso(baseTime, timezone)).getTime()
  if (Number.isNaN(base)) return ''
  return new Date(base - offsetMinutes * 60_000).toISOString()
}

export function toApiTimePayload(input: Record<string, any>, timezone = displayTimezone) {
  const out = { ...input }
  for (const key of ['startTime', 'endTime', 'deadlineTime', 'remindAt']) if (out[key]) out[key] = zonedDateTimeToIso(out[key], timezone)
  return out
}

export function normalizeTimelineItem(item: any, sourceType: 'schedule' | 'team_task'): TimelineItem {
  const kind = timelineKind({ ...item, sourceType })
  const { sortAt } = timelineTimeRange({ ...item, sourceType, kind })
  return { ...item, sourceType, kind, kindLabel: kindName(kind), sortAt, statusText: item.assignStatus || item.status, sourceLabel: sourceType === 'team_task' ? (item.teamName || '团队任务') : (item.groupName || '个人日程') }
}

function kindName(kind: string) { return kind === 'point_event' ? '安排事项' : kind === 'duration_task' ? '时间段任务' : '待办任务' }

export function buildMonthDays(items: any[], timezone = displayTimezone): CalendarDay[] {
  const now = currentDateParts(timezone); const firstDay = new Date(Date.UTC(now.year, now.month - 1, 1)).getUTCDay(); const days: CalendarDay[] = []
  const pad = (n: number) => String(n).padStart(2, '0')
  for (let i = 0; i < (firstDay + 6) % 7; i++) days.push({ day: '', today: false, items: [] })
  const count = new Date(Date.UTC(now.year, now.month, 0)).getUTCDate()
  for (let d = 1; d <= count; d++) {
    const key = `${now.year}-${pad(now.month)}-${pad(d)}`
    days.push({ day: d, today: d === now.day, items: items.filter(item => occursOnDate(item, key, timezone)) })
  }
  return days
}

export function primaryTime(item: any) { return item.deadlineTime || item.endTime || item.startTime || item.createdAt || '' }

export function occursOnDate(item: any, dateKey: string, timezone = displayTimezone) {
  return timelineOccursOnDate(item, dateKey, timezone)
}

export function formatTime(value: string, timezone = displayTimezone) {
  if (!value) return '未设置'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { timeZone: timezone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

export function countdown(value: string, timeType?: TimeType) {
  if (!value) return '无截止时间'
  const diff = new Date(value).getTime() - Date.now()
  const abs = Math.abs(diff)
  const minutes = Math.floor(abs / 60000)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)
  if (diff < 0) {
    if (timeType === 'point_event') return '已过'
    return days > 0 ? `已逾期 ${days} 天` : hours > 0 ? `已逾期 ${hours} 小时` : `已逾期 ${minutes} 分钟`
  }
  if (hours >= 24) return `剩余 ${days} 天`
  return `剩余 ${hours} 小时 ${minutes % 60} 分钟`
}

export function urgency(value: string, timeType?: TimeType) {
  if (!value) return ''
  const diff = new Date(value).getTime() - Date.now()
  if (diff < 0) return timeType === 'point_event' ? 'past' : 'danger'
  if (diff <= 10 * 60000) return 'danger'
  if (diff <= 30 * 60000) return 'warning'
  return ''
}

export function isOverdue(item: any) {
  return isTimelineOverdue(item)
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
    pending: '待办', paused: '已暂停', completed: '已完成', cancelled: '已取消',
    active: '进行中', unassigned: '待重新分配', all_rejected: '全部拒绝',
    pending_approval: '待管理员审批', approval_rejected: '审批未通过',
    approved: '审批通过',
    accepted: '已接受', rejected: '已拒绝'
  }
  return map[s] || s
}

export function canDeleteTeamTask(task: { status: string } | null | undefined) {
  return Boolean(task && task.status !== 'completed')
}

export function canCorrectTeamTaskAssignee(task: { status: string; approvalStatus?: string } | null | undefined) {
  return Boolean(task && task.status !== 'completed' && task.approvalStatus === 'approved')
}
