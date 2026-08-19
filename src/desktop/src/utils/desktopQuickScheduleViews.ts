import type { ScheduleViewMode } from '@web/types'

export type QuickSource = 'personal' | 'team'
export type QuickViewKind =
  | 'timeline'
  | 'create'
  | 'team-breakdown'
  | 'schedule-detail'
  | 'schedule-edit'
  | 'task-detail'
  | 'task-edit'
  | 'fatigue-survey'

export const QUICK_SCHEDULE_PAGE_SIZE = 32

export function defaultQuickScrollPositions(): Record<ScheduleViewMode, number> {
  return { time: 0, group: 0, urgency: 0, fatigue: 0 }
}

export function quickPreferenceKeys(userId: number) {
  return {
    view: `dayliane_desktop_quick_schedule_view_${userId}`,
    source: `dayliane_desktop_quick_source_${userId}`,
    scroll: `dayliane_desktop_quick_schedule_scroll_${userId}`,
  }
}

export function quickScheduleSort(mode: ScheduleViewMode) {
  if (mode === 'group') return 'manual'
  if (mode === 'urgency') return 'urgency_desc'
  if (mode === 'fatigue') return 'fatigue_desc'
  return 'time_asc'
}

export function quickScheduleQuery(mode: ScheduleViewMode) {
  return new URLSearchParams({
    page: '1',
    size: String(QUICK_SCHEDULE_PAGE_SIZE),
    status: 'pending',
    sort: quickScheduleSort(mode),
    viewMode: mode,
    quickScope: 'true',
  })
}

export function entriesForQuickSource<TPersonal, TTeam>(source: QuickSource, personal: TPersonal[], team: TTeam[]) {
  return source === 'personal' ? personal : team
}

export function scheduleDataRevision(result: { dataRevision?: unknown; revision?: unknown }) {
  const value = result.dataRevision ?? result.revision
  if (typeof value === 'number') return Number.isSafeInteger(value) && value >= 0 ? value : null
  if (typeof value !== 'string' || !/^\d+$/.test(value.trim())) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

export function shouldAcceptScheduleRevision(current: number | null, incoming: number | null) {
  if (incoming === null) return current === null
  return current === null || incoming >= current
}

export function shouldDeferQuickTarget(kind: QuickViewKind) {
  return ['create', 'schedule-edit', 'task-edit', 'team-breakdown'].includes(kind)
}
