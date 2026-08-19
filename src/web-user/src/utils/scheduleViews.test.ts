import { describe, expect, it } from 'vitest'
import { buildTodayScheduleSections, type HomeScheduleLike } from './scheduleViews'

const now = new Date('2026-08-17T12:00:00Z').getTime()

function schedule(id: number, patch: Partial<HomeScheduleLike> = {}): HomeScheduleLike {
  return {
    id,
    status: 'pending',
    groupId: null,
    groupName: '',
    urgencyLevel: 3,
    fatigueLevel: 3,
    timeType: 'deadline_task',
    deadlineTime: '2026-08-17T14:00:00Z',
    ...patch,
  }
}

describe('home schedule view sections', () => {
  it('separates passed schedules from the remaining schedules in time view', () => {
    const sections = buildTodayScheduleSections([
      schedule(1, { timeType: 'point_event', startTime: '2026-08-17T10:00:00Z', deadlineTime: '' }),
      schedule(2),
    ], 'time', [], now)

    expect(sections.map(section => section.name)).toEqual(['已逾期或已错过', '今天'])
    expect(sections.map(section => section.items.map(item => item.id))).toEqual([[1], [2]])
  })

  it('uses the configured personal group order', () => {
    const sections = buildTodayScheduleSections([
      schedule(1, { groupId: 20, groupName: '生活' }),
      schedule(2, { groupId: 10, groupName: '工作' }),
      schedule(3, { groupId: null, groupName: '' }),
    ], 'group', [
      { id: 10, sortOrder: 1 },
      { id: 20, sortOrder: 2 },
    ], now)

    expect(sections.map(section => section.name)).toEqual(['工作', '生活', '未分组'])
  })

  it('orders urgency and fatigue sections from level five to level one', () => {
    const schedules = [
      schedule(1, { urgencyLevel: 2, fatigueLevel: 1 }),
      schedule(2, { urgencyLevel: 5, fatigueLevel: 4 }),
      schedule(3, { urgencyLevel: 3, fatigueLevel: 5 }),
    ]

    expect(buildTodayScheduleSections(schedules, 'urgency', [], now).map(section => section.name))
      .toEqual(['非常紧急', '普通', '较低'])
    expect(buildTodayScheduleSections(schedules, 'fatigue', [], now).map(section => section.name))
      .toEqual(['非常劳累', '比较劳累', '几乎不累'])
  })

  it('uses urgency and id as stable time-view tie breakers', () => {
    const sections = buildTodayScheduleSections([
      schedule(3, { urgencyLevel: 4 }),
      schedule(1, { urgencyLevel: 4 }),
      schedule(2, { urgencyLevel: 5 }),
    ], 'time', [], now)

    expect(sections[0].items.map(item => item.id)).toEqual([2, 1, 3])
  })

  it('keeps overdue work first and then honors manual order inside a group', () => {
    const sections = buildTodayScheduleSections([
      schedule(1, { groupId: 10, groupName: '工作', sortOrder: 2 }),
      schedule(2, { groupId: 10, groupName: '工作', sortOrder: 1 }),
      schedule(3, { groupId: 10, groupName: '工作', sortOrder: 99, deadlineTime: '2026-08-17T10:00:00Z' }),
    ], 'group', [{ id: 10, sortOrder: 1 }], now)

    expect(sections[0].items.map(item => item.id)).toEqual([3, 2, 1])
  })

  it('uses time, fatigue, and id inside the same urgency level', () => {
    const sections = buildTodayScheduleSections([
      schedule(4, { urgencyLevel: 5, fatigueLevel: 4, deadlineTime: '2026-08-17T15:00:00Z' }),
      schedule(3, { urgencyLevel: 5, fatigueLevel: 4, deadlineTime: '2026-08-17T14:00:00Z' }),
      schedule(2, { urgencyLevel: 5, fatigueLevel: 5, deadlineTime: '2026-08-17T14:00:00Z' }),
      schedule(1, { urgencyLevel: 5, fatigueLevel: 1, deadlineTime: '2026-08-17T10:00:00Z' }),
    ], 'urgency', [], now)

    expect(sections[0].items.map(item => item.id)).toEqual([1, 2, 3, 4])
  })

  it('uses urgency, time, and id inside the same fatigue level', () => {
    const sections = buildTodayScheduleSections([
      schedule(4, { fatigueLevel: 5, urgencyLevel: 4, deadlineTime: '2026-08-17T13:00:00Z' }),
      schedule(3, { fatigueLevel: 5, urgencyLevel: 5, deadlineTime: '2026-08-17T15:00:00Z' }),
      schedule(2, { fatigueLevel: 5, urgencyLevel: 5, deadlineTime: '2026-08-17T14:00:00Z' }),
      schedule(1, { fatigueLevel: 5, urgencyLevel: 5, deadlineTime: '2026-08-17T14:00:00Z' }),
    ], 'fatigue', [], now)

    expect(sections[0].items.map(item => item.id)).toEqual([1, 2, 3, 4])
  })
})
