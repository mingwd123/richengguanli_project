import type {
  TimeType,
  TimelineItem,
  TimelinePresentationStatus,
  TimelineStats,
  TimelineTimeRange
} from '../types'

export type TimelineItemLike = Pick<Partial<TimelineItem>,
  'sourceType' | 'kind' | 'status' | 'assignStatus' | 'startTime' | 'endTime' | 'deadlineTime' | 'sortAt'
> & {
  timeType?: TimeType
  createdAt?: string
}

function validTimestamp(value?: string) {
  return !!value && !Number.isNaN(new Date(value).getTime())
}

function timestamp(value?: string) {
  return value ? new Date(value).getTime() : Number.NaN
}

function dateKeyInTimezone(value: number | string | Date, timezone: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(date)
  const part = (type: string) => parts.find(entry => entry.type === type)?.value || ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function timelineKind(item: TimelineItemLike): TimeType {
  if (item.kind) return item.kind
  if (item.timeType === 'point_event' || item.timeType === 'duration_task' || item.timeType === 'deadline_task') return item.timeType
  return item.sourceType === 'team_task' && item.startTime && item.deadlineTime
    ? 'duration_task'
    : 'deadline_task'
}

export function timelineTimeRange(item: TimelineItemLike): TimelineTimeRange {
  const kind = timelineKind(item)
  const durationEnd = item.endTime || item.deadlineTime || ''

  if (kind === 'duration_task' && validTimestamp(item.startTime) && validTimestamp(durationEnd)) {
    return {
      kind,
      startAt: item.startTime!,
      endAt: durationEnd,
      sortAt: item.startTime!,
      isDuration: true
    }
  }

  const at = kind === 'point_event'
    ? item.startTime || item.sortAt || ''
    : item.deadlineTime || item.endTime || item.startTime || item.sortAt || ''

  return { kind, startAt: at, endAt: at, sortAt: at, isDuration: false }
}

function terminalStatus(item: TimelineItemLike): Extract<TimelinePresentationStatus, 'completed' | 'cancelled' | 'rejected'> | null {
  if (item.status === 'completed' || item.assignStatus === 'completed') return 'completed'
  if (item.assignStatus === 'rejected') return 'rejected'
  if (item.status === 'cancelled' || item.status === 'all_rejected') return 'cancelled'
  return null
}

export function timelinePresentationStatus(item: TimelineItemLike, now: number | Date = Date.now()): TimelinePresentationStatus {
  const terminal = terminalStatus(item)
  if (terminal) return terminal

  const range = timelineTimeRange(item)
  const startAt = timestamp(range.startAt)
  const endAt = timestamp(range.endAt)
  const nowTs = typeof now === 'number' ? now : now.getTime()
  if (Number.isNaN(startAt) || Number.isNaN(endAt)) return 'unscheduled'

  if (range.kind === 'point_event') return startAt < nowTs ? 'past' : 'upcoming'
  if (range.isDuration) {
    if (endAt < nowTs) return 'overdue'
    return startAt <= nowTs ? 'active' : 'upcoming'
  }
  return endAt < nowTs ? 'overdue' : 'upcoming'
}

export function isTimelineOverdue(item: TimelineItemLike, now: number | Date = Date.now()) {
  return timelinePresentationStatus(item, now) === 'overdue'
}

export function isTimelineItemOpen(item: TimelineItemLike) {
  const status = terminalStatus(item)
  return !status
}

function dayStartTimestamp(dateKey: string, timezone: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateKey)
  if (!match) return Number.NaN
  const [, year, month, day] = match.map(Number)
  const localMidnight = Date.UTC(year, month - 1, day)
  const offsetAt = (value: number) => {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: timezone,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
    }).formatToParts(new Date(value))
    const part = (type: string) => Number(parts.find(entry => entry.type === type)?.value || 0)
    return Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second')) - value
  }
  const firstPass = localMidnight - offsetAt(localMidnight)
  return localMidnight - offsetAt(firstPass)
}

function nextDateKey(dateKey: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateKey)
  if (!match) return ''
  const [, year, month, day] = match.map(Number)
  const next = new Date(Date.UTC(year, month - 1, day + 1))
  return `${next.getUTCFullYear()}-${String(next.getUTCMonth() + 1).padStart(2, '0')}-${String(next.getUTCDate()).padStart(2, '0')}`
}

/** Whether an item occupies any portion of the requested local calendar day. */
export function timelineOccursOnDate(item: TimelineItemLike, dateKey: string, timezone: string) {
  const range = timelineTimeRange(item)
  if (!range.isDuration) return dateKeyInTimezone(range.startAt, timezone) === dateKey

  const startAt = timestamp(range.startAt)
  const endAt = timestamp(range.endAt)
  const dayStart = dayStartTimestamp(dateKey, timezone)
  const dayEnd = dayStartTimestamp(nextDateKey(dateKey), timezone)
  if ([startAt, endAt, dayStart, dayEnd].some(Number.isNaN)) return false
  if (startAt === endAt) return dateKeyInTimezone(range.startAt, timezone) === dateKey
  return startAt < dayEnd && endAt > dayStart
}

export function buildTimelineStats(items: TimelineItemLike[], options: { now?: number | Date, timezone: string } ): TimelineStats {
  const now = options.now ?? Date.now()
  const nowValue = typeof now === 'number' ? now : now.getTime()
  const todayKey = dateKeyInTimezone(nowValue, options.timezone)
  const stats: TimelineStats = { today: 0, overdue: 0, upcoming: 0, active: 0, past: 0 }

  for (const item of items) {
    if (!isTimelineItemOpen(item)) continue
    if (timelineOccursOnDate(item, todayKey, options.timezone)) stats.today += 1
    const status = timelinePresentationStatus(item, nowValue)
    if (status === 'overdue') stats.overdue += 1
    if (status === 'upcoming') stats.upcoming += 1
    if (status === 'active') stats.active += 1
    if (status === 'past') stats.past += 1
  }

  return stats
}
