import { describe, expect, it } from 'vitest'
import {
  entriesForQuickSource,
  quickPreferenceKeys,
  quickScheduleQuery,
  scheduleDataRevision,
  shouldAcceptScheduleRevision,
  shouldDeferQuickTarget,
} from './desktopQuickScheduleViews'

describe('desktop quick schedule views', () => {
  it('requests a bounded server-side quick scope for every personal mode', () => {
    expect(Object.fromEntries(quickScheduleQuery('time'))).toMatchObject({
      page: '1',
      size: '32',
      status: 'pending',
      sort: 'time_asc',
      viewMode: 'time',
      quickScope: 'true',
    })
    expect(quickScheduleQuery('group').get('sort')).toBe('manual')
    expect(quickScheduleQuery('urgency').get('sort')).toBe('urgency_desc')
    expect(quickScheduleQuery('fatigue').get('sort')).toBe('fatigue_desc')
  })

  it('keeps personal schedules and team tasks in separate source pipelines', () => {
    const personal = [{ id: 1, source: 'schedule' }]
    const team = [{ id: 2, source: 'team_task' }]
    expect(entriesForQuickSource('personal', personal, team)).toBe(personal)
    expect(entriesForQuickSource('team', personal, team)).toBe(team)
    expect(entriesForQuickSource('personal', personal, team)).not.toContain(team[0])
  })

  it('isolates mode, source, and scroll preferences by user', () => {
    const first = quickPreferenceKeys(11)
    const second = quickPreferenceKeys(12)
    expect(first.view).not.toBe(second.view)
    expect(first.source).not.toBe(second.source)
    expect(first.scroll).not.toBe(second.scroll)
  })

  it('normalizes schedule revisions and rejects older responses', () => {
    expect(scheduleDataRevision({ dataRevision: 12, revision: '11' })).toBe(12)
    expect(scheduleDataRevision({ revision: '13' })).toBe(13)
    expect(scheduleDataRevision({ revision: 'invalid' })).toBeNull()
    expect(shouldAcceptScheduleRevision(null, null)).toBe(true)
    expect(shouldAcceptScheduleRevision(12, null)).toBe(false)
    expect(shouldAcceptScheduleRevision(12, 11)).toBe(false)
    expect(shouldAcceptScheduleRevision(12, 12)).toBe(true)
    expect(shouldAcceptScheduleRevision(12, 13)).toBe(true)
  })

  it('defers survey navigation while an editor or AI draft is active', () => {
    expect(shouldDeferQuickTarget('create')).toBe(true)
    expect(shouldDeferQuickTarget('schedule-edit')).toBe(true)
    expect(shouldDeferQuickTarget('team-breakdown')).toBe(true)
    expect(shouldDeferQuickTarget('timeline')).toBe(false)
    expect(shouldDeferQuickTarget('schedule-detail')).toBe(false)
  })
})
