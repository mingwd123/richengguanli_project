import { afterEach, describe, expect, it, vi } from 'vitest'
import { canCorrectTeamTaskAssignee, canDeleteTeamTask, countdown, normalizeTimelineItem, occursOnDate, reminderTimeForOffset, setDisplayTimezone, sortByPriority, toApiTimePayload, toDatetimeLocalInTimezone, toSchedulePayload, urgency, zonedDateTimeToIso, formatProgressPercent, canTrackDailyProgress, isProgressComplete, progressCompletionActionLabel, progressSubmitDialogTitle } from './helpers'
import { buildTimelineStats, timelinePresentationStatus, timelineTimeRange } from './timeline'

describe('schedule display helpers', () => {
  afterEach(() => {
    vi.useRealTimers()
    setDisplayTimezone('Asia/Shanghai')
  })

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

  it('round-trips editor values through the configured user timezone', () => {
    const instant = '2026-08-09T01:30:00.000Z'
    expect(toDatetimeLocalInTimezone(instant, 'Asia/Shanghai')).toBe('2026-08-09T09:30')
    expect(zonedDateTimeToIso('2026-08-09T09:30', 'Asia/Shanghai')).toBe(instant)
  })

  it('does not use the device timezone when editing another user timezone', () => {
    expect(toDatetimeLocalInTimezone('2026-01-15T17:45:00.000Z', 'America/New_York')).toBe('2026-01-15T12:45')
    expect(zonedDateTimeToIso('2026-01-15T12:45', 'America/New_York')).toBe('2026-01-15T17:45:00.000Z')
  })

  it('keeps timezone-free AI draft values as user-local editor values', () => {
    expect(toDatetimeLocalInTimezone('2026-01-15 12:45', 'America/New_York')).toBe('2026-01-15T12:45')
  })

  it('builds schedule and team task payloads in the configured user timezone', () => {
    setDisplayTimezone('America/New_York')
    expect(toSchedulePayload({
      title: 'Review', description: '', groupId: '1', groupName: 'Work', timeType: 'point_event',
      startTime: '2026-01-15T12:45', endTime: '', deadlineTime: '', remindAt: '2026-01-15T11:45'
    })).toMatchObject({
      startTime: '2026-01-15T17:45:00.000Z',
      remindAt: '2026-01-15T16:45:00.000Z'
    })
    expect(toApiTimePayload({ deadlineTime: '2026-01-15T12:45' })).toMatchObject({ deadlineTime: '2026-01-15T17:45:00.000Z' })
  })

  it('calculates reminder offsets from a datetime-local value in the user timezone', () => {
    expect(reminderTimeForOffset('2026-01-15T12:45', 60, 'America/New_York')).toBe('2026-01-15T16:45:00.000Z')
  })

  it('only enables daily progress for non-recurring tasks', () => {
    const base = {
      title: 'Long task', description: '', groupId: '1', groupName: 'Work',
      startTime: '', endTime: '', deadlineTime: '', remindAt: ''
    }
    expect(toSchedulePayload({ ...base, timeType: 'duration_task', progressTrackingEnabled: true }))
      .toMatchObject({ progressTrackingEnabled: true })
    expect(toSchedulePayload({ ...base, timeType: 'deadline_task', progressTrackingEnabled: true, rrule: 'FREQ=DAILY' }))
      .toMatchObject({ progressTrackingEnabled: false })
    expect(toSchedulePayload({ ...base, timeType: 'point_event', progressTrackingEnabled: true }))
      .toMatchObject({ progressTrackingEnabled: false })
    expect(toSchedulePayload({ ...base, timeType: 'duration_task', progressTrackingEnabled: false }))
      .toMatchObject({ progressTrackingEnabled: false })
  })

  it('omits the daily progress flag when the caller does not carry it', () => {
    // 桌面端等调用方的表单里没有这个字段，不能因此被当成「关闭每日进度」发出去。
    const payload = toSchedulePayload({
      title: 'Desktop edit', description: '', groupId: '1', groupName: 'Work', timeType: 'duration_task',
      startTime: '', endTime: '', deadlineTime: '', remindAt: ''
    })
    expect(Object.prototype.hasOwnProperty.call(payload, 'progressTrackingEnabled')).toBe(false)
    expect(payload.rrule).toBe('')
  })

  it('formats daily progress percentages for display', () => {
    expect(formatProgressPercent(0)).toBe('0%')
    expect(formatProgressPercent(40)).toBe('40%')
    expect(formatProgressPercent(33.3)).toBe('33.3%')
    expect(formatProgressPercent(null)).toBe('0%')
    expect(formatProgressPercent(undefined)).toBe('0%')
  })

  it('shares the daily progress availability rule between web and desktop', () => {
    expect(canTrackDailyProgress({ timeType: 'deadline_task' })).toBe(true)
    expect(canTrackDailyProgress({ timeType: 'duration_task' })).toBe(true)
    expect(canTrackDailyProgress({ timeType: 'point_event' })).toBe(false)
    expect(canTrackDailyProgress({ timeType: 'deadline_task', rrule: 'FREQ=DAILY' })).toBe(false)
    expect(canTrackDailyProgress({})).toBe(false)
  })

  // 取消后恢复或修正回退再补回时，任务可能停在「待办 + 累计 100%」，
  // 此时必须给出「标记完成」入口，不能只剩「完成剩余进度」这种无剩余可提交的动作。
  it('switches the completion action once progress has reached 100%', () => {
    expect(isProgressComplete(0)).toBe(false)
    expect(isProgressComplete(99.9)).toBe(false)
    expect(isProgressComplete(100)).toBe(true)
    expect(isProgressComplete(null)).toBe(false)

    expect(progressCompletionActionLabel(70)).toBe('完成剩余进度')
    expect(progressCompletionActionLabel(100)).toBe('标记完成')
    expect(progressCompletionActionLabel(null)).toBe('完成剩余进度')
  })

  it('names the progress dialog after the action it performs', () => {
    expect(progressSubmitDialogTitle(40, 40)).toBe('提交每日进度')
    expect(progressSubmitDialogTitle(40, 100)).toBe('提交剩余进度并完成')
    expect(progressSubmitDialogTitle(100, 100)).toBe('标记任务完成')
  })
})

describe('team task terminal-state guards', () => {
  it('blocks deletion after completion', () => {
    expect(canDeleteTeamTask({ status: 'active' })).toBe(true)
    expect(canDeleteTeamTask({ status: 'completed' })).toBe(false)
  })

  it('allows status correction only for approved non-completed tasks', () => {
    expect(canCorrectTeamTaskAssignee({ status: 'active', approvalStatus: 'approved' })).toBe(true)
    expect(canCorrectTeamTaskAssignee({ status: 'completed', approvalStatus: 'approved' })).toBe(false)
    expect(canCorrectTeamTaskAssignee({ status: 'pending_approval', approvalStatus: 'pending' })).toBe(false)
  })
})
