import { afterEach, describe, expect, it, vi } from 'vitest'
import { countdown, normalizeTimelineItem, occursOnDate, reminderTimeForOffset, sortByPriority, urgency } from './helpers'
import { buildTimelineStats, timelinePresentationStatus, timelineTimeRange } from './timeline'

describe('schedule display helpers', () => {
  afterEach(() => vi.useRealTimers())

  it('shows a duration task on every local date in its range', () => {
    const task = {
      sourceType: 'team_task',
      startTime: '2026-07-30T16:00:00Z',
      deadlineTime: '2026-08-02T15:00:00Z'
    }
    expect(occursOnDate(task, '2026-07-31', 'Asia/Shanghai')).toBe(true)
    expect(occursOnDate(task, '2026-08-01', 'Asia/Shanghai')).toBe(true)
    expect(occursOnDate(task, '2026-08-02', 'Asia/Shanghai')).toBe(true)
    expect(occursOnDate(task, '2026-08-03', 'Asia/Shanghai')).toBe(false)
  })

  it('normalizes start plus deadline team tasks as durations', () => {
    const item = normalizeTimelineItem({
      id: 1,
      title: '跨日任务',
      status: 'active',
      startTime: '2026-07-31T01:00:00Z',
      deadlineTime: '2026-08-02T01:00:00Z'
    }, 'team_task')
    expect(item.kind).toBe('duration_task')
    expect(item.sortAt).toBe('2026-07-31T01:00:00Z')
  })

  it('keeps overdue open items ahead of future items', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-31T12:00:00Z'))
    const sorted = sortByPriority([
      { id: 1, status: 'pending', deadlineTime: '2026-08-01T12:00:00Z' },
      { id: 2, status: 'pending', deadlineTime: '2026-07-30T12:00:00Z' }
    ])
    expect(sorted.map(item => item.id)).toEqual([2, 1])
  })

  it('keeps a cross-day duration task in today stats while it is active', () => {
    const now = new Date('2026-08-01T04:00:00Z')
    const task = normalizeTimelineItem({
      id: 1,
      title: 'Cross-day task',
      status: 'active',
      startTime: '2026-08-01T01:00:00Z',
      deadlineTime: '2026-08-02T01:00:00Z'
    }, 'team_task')
    expect(buildTimelineStats([task], { now, timezone: 'Asia/Shanghai' })).toMatchObject({ today: 1, active: 1, overdue: 0 })
  })

  it('treats passed point events as past instead of overdue', () => {
    const now = new Date('2026-08-01T04:00:00Z')
    const event = normalizeTimelineItem({
      id: 1,
      title: 'Past meeting',
      status: 'pending',
      timeType: 'point_event',
      startTime: '2026-08-01T03:00:00Z'
    }, 'schedule')
    expect(timelinePresentationStatus(event, now)).toBe('past')
    expect(buildTimelineStats([event], { now, timezone: 'Asia/Shanghai' })).toMatchObject({ overdue: 0, past: 1 })
  })

  it('labels passed point events as past without overdue styling', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-04T04:00:00Z'))
    const passedAt = '2026-08-04T03:00:00Z'
    expect(countdown(passedAt, 'point_event')).toBe('已过')
    expect(urgency(passedAt, 'point_event')).toBe('past')
    expect(countdown(passedAt, 'deadline_task')).toContain('已逾期')
    expect(urgency(passedAt, 'deadline_task')).toBe('danger')
  })

  it('marks passed deadline and duration tasks as overdue', () => {
    const now = new Date('2026-08-01T04:00:00Z')
    const deadline = normalizeTimelineItem({
      id: 1, title: 'Deadline task', status: 'pending', timeType: 'deadline_task', deadlineTime: '2026-08-01T03:00:00Z'
    }, 'schedule')
    const duration = normalizeTimelineItem({
      id: 2, title: 'Duration task', status: 'pending', timeType: 'duration_task', startTime: '2026-08-01T01:00:00Z', endTime: '2026-08-01T03:00:00Z'
    }, 'schedule')
    expect(timelinePresentationStatus(deadline, now)).toBe('overdue')
    expect(timelinePresentationStatus(duration, now)).toBe('overdue')
  })

  it('uses a duration start time as its timeline sort position', () => {
    const range = timelineTimeRange({
      sourceType: 'schedule', timeType: 'duration_task', startTime: '2026-08-01T01:00:00Z', endTime: '2026-08-01T03:00:00Z'
    })
    expect(range).toMatchObject({ sortAt: '2026-08-01T01:00:00Z', isDuration: true })
  })

  it('calculates a reminder time from the chosen offset', () => {
    expect(reminderTimeForOffset('2026-08-02T10:00:00+08:00', 60)).toBe('2026-08-02T01:00:00.000Z')
    expect(reminderTimeForOffset('', 60)).toBe('')
  })
})
