import type { ScheduleLevel, ScheduleViewMode } from '../types'
import { primaryTime } from './helpers'
import { timelinePresentationStatus } from './timeline'

export type HomeScheduleLike = {
  id: number
  status: string
  sortOrder?: number
  groupId?: number | null
  groupName?: string
  urgencyLevel?: ScheduleLevel
  urgencyLabel?: string
  fatigueLevel?: ScheduleLevel
  fatigueLabel?: string
  timeType?: 'point_event' | 'deadline_task' | 'duration_task'
  startTime?: string
  endTime?: string
  deadlineTime?: string
  createdAt?: string
}

export type HomeScheduleSection<T extends HomeScheduleLike> = {
  key: string
  name: string
  items: T[]
}

type GroupOrder = { id: number; sortOrder?: number }
type SectionDefinition = { key: string; name: string; rank: number }

const urgencyNames = ['不紧急', '较低', '普通', '紧急', '非常紧急']
const fatigueNames = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']

function normalizedLevel(value: unknown) {
  const level = Number(value || 3)
  return Math.max(1, Math.min(5, Number.isFinite(level) ? level : 3))
}

function timePresentation(item: HomeScheduleLike, now: number) {
  return timelinePresentationStatus({ ...item, sourceType: 'schedule' }, now)
}

function sectionFor<T extends HomeScheduleLike>(
  item: T,
  mode: ScheduleViewMode,
  groupRanks: Map<number, number>,
  now: number
): SectionDefinition {
  if (mode === 'group') {
    const name = item.groupName?.trim() || '未分组'
    const rank = item.groupId ? groupRanks.get(item.groupId) ?? Number.MAX_SAFE_INTEGER - 1 : Number.MAX_SAFE_INTEGER
    return { key: `group:${item.groupId || name}`, name, rank }
  }
  if (mode === 'urgency') {
    const level = normalizedLevel(item.urgencyLevel)
    return { key: `urgency:${level}`, name: item.urgencyLabel || urgencyNames[level - 1], rank: 6 - level }
  }
  if (mode === 'fatigue') {
    const level = normalizedLevel(item.fatigueLevel)
    return { key: `fatigue:${level}`, name: item.fatigueLabel || fatigueNames[level - 1], rank: 6 - level }
  }
  const presentation = timePresentation(item, now)
  const overdue = presentation === 'overdue' || presentation === 'past'
  return overdue
    ? { key: 'time:overdue', name: '已逾期或已错过', rank: 0 }
    : { key: 'time:today', name: '今天', rank: 1 }
}

function scheduleTime(item: HomeScheduleLike) {
  const value = primaryTime(item)
  const timestamp = value ? new Date(value).getTime() : Number.POSITIVE_INFINITY
  return Number.isNaN(timestamp) ? Number.POSITIVE_INFINITY : timestamp
}

function isOverdueOrPast(item: HomeScheduleLike, now: number) {
  return ['overdue', 'past'].includes(timePresentation(item, now))
}

function compareTime(a: HomeScheduleLike, b: HomeScheduleLike) {
  return scheduleTime(a) - scheduleTime(b)
}

function compareLevelDesc(a: unknown, b: unknown) {
  return normalizedLevel(b) - normalizedLevel(a)
}

function compareHomeSchedules(a: HomeScheduleLike, b: HomeScheduleLike, mode: ScheduleViewMode, now: number) {
  const overdueFirst = Number(isOverdueOrPast(b, now)) - Number(isOverdueOrPast(a, now))
  const byId = a.id - b.id

  if (mode === 'group') {
    return overdueFirst
      || Number(a.sortOrder ?? Number.MAX_SAFE_INTEGER) - Number(b.sortOrder ?? Number.MAX_SAFE_INTEGER)
      || compareTime(a, b)
      || byId
  }
  if (mode === 'urgency') {
    return overdueFirst
      || compareTime(a, b)
      || compareLevelDesc(a.fatigueLevel, b.fatigueLevel)
      || byId
  }
  if (mode === 'fatigue') {
    return compareLevelDesc(a.urgencyLevel, b.urgencyLevel)
      || compareTime(a, b)
      || byId
  }
  return overdueFirst
    || compareTime(a, b)
    || compareLevelDesc(a.urgencyLevel, b.urgencyLevel)
    || byId
}

export function buildTodayScheduleSections<T extends HomeScheduleLike>(
  schedules: T[],
  mode: ScheduleViewMode,
  groups: GroupOrder[],
  now = Date.now()
): HomeScheduleSection<T>[] {
  const groupRanks = new Map(
    [...groups]
      .sort((a, b) => Number(a.sortOrder || 0) - Number(b.sortOrder || 0))
      .map((group, index) => [group.id, index])
  )
  const rows = new Map<string, HomeScheduleSection<T> & { rank: number }>()
  const activeSchedules = schedules
    .filter(item => !['completed', 'cancelled'].includes(item.status))
    .sort((a, b) => compareHomeSchedules(a, b, mode, now))

  for (const schedule of activeSchedules) {
    const definition = sectionFor(schedule, mode, groupRanks, now)
    const section = rows.get(definition.key) || { ...definition, items: [] }
    section.items.push(schedule)
    rows.set(definition.key, section)
  }

  return [...rows.values()]
    .sort((a, b) => a.rank - b.rank || a.name.localeCompare(b.name, 'zh-CN'))
    .map(({ rank: _rank, ...section }) => section)
}
