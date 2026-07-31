import { afterEach, describe, expect, it, vi } from 'vitest'
import { normalizeTimelineItem, occursOnDate, sortByPriority } from './helpers'

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
    expect(item.sortAt).toBe('2026-08-02T01:00:00Z')
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
})
