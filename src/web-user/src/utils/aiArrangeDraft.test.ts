import { describe, expect, it } from 'vitest'
import type { AiArrangeSuggestion, Schedule } from '../types'
import { aiSuggestionActionState, buildAiReorderDraft } from './aiArrangeDraft'

function schedule(patch: Partial<Schedule> & Pick<Schedule, 'id' | 'title' | 'sortOrder'>): Schedule {
  return {
    id: patch.id,
    title: patch.title,
    description: '',
    groupId: 8,
    groupName: '工作',
    sortOrder: patch.sortOrder,
    timeType: 'point_event',
    startTime: '2026-09-10T01:00:00Z',
    endTime: '',
    deadlineTime: '',
    status: 'pending',
    urgencyLevel: 3,
    urgencyLabel: '普通',
    fatigueLevel: 3,
    fatigueLabel: '一般',
    fatigueWeight: 3,
    completedAt: '',
    completedFatigueLevel: null,
    completedFatigueWeight: null,
    reminderCount: 0,
    pendingReminders: [],
    createdAt: '',
    updatedAt: '',
    ...patch,
  }
}

const reorderSuggestion: AiArrangeSuggestion = {
  type: 'reorder',
  date: '2026-09-10',
  scheduleIds: [2, 1],
  titles: ['高消耗任务', '低消耗任务'],
  reason: '按疲劳等级排序',
}

describe('AI arrange draft validation', () => {
  it('accepts complete reschedule and reorder actions', () => {
    expect(aiSuggestionActionState({
      type: 'reschedule',
      scheduleId: 3,
      targetDate: '2026-09-12',
      reason: '',
    }).enabled).toBe(true)
    expect(aiSuggestionActionState(reorderSuggestion).enabled).toBe(true)
  })

  it('rejects malformed suggestion identifiers and dates', () => {
    expect(aiSuggestionActionState({
      type: 'reschedule',
      scheduleId: 0,
      targetDate: '2026-02-30',
      reason: '',
    })).toEqual({ enabled: false, reason: '建议缺少有效的日程或目标日期，请重新分析' })
    expect(aiSuggestionActionState({
      type: 'reorder',
      date: '2026-09-10',
      scheduleIds: [1, 1],
      reason: '',
    }).enabled).toBe(false)
  })

  it('keeps split disabled until the API supplies concrete child drafts', () => {
    expect(aiSuggestionActionState({
      type: 'split',
      scheduleId: 9,
      title: '复杂任务',
      reason: '建议拆分',
    })).toEqual({
      enabled: false,
      reason: '当前建议未包含子任务标题和安排日期，无法生成可确认的拆分草稿',
    })
  })

  it('builds an editable same-group reorder draft from current schedule data', () => {
    const result = buildAiReorderDraft(reorderSuggestion, [
      schedule({ id: 1, title: '低消耗任务', sortOrder: 10 }),
      schedule({ id: 2, title: '高消耗任务', sortOrder: 20 }),
    ], 'Asia/Shanghai')

    expect(result.reason).toBe('')
    expect(result.draft).toMatchObject({
      date: '2026-09-10',
      groupId: 8,
      groupName: '工作',
      items: [
        { id: 2, title: '高消耗任务' },
        { id: 1, title: '低消耗任务' },
      ],
    })
  })

  it('rejects reorder suggestions that cross modules', () => {
    const result = buildAiReorderDraft(reorderSuggestion, [
      schedule({ id: 1, title: '低消耗任务', sortOrder: 10 }),
      schedule({ id: 2, title: '高消耗任务', sortOrder: 20, groupId: 12, groupName: '学习' }),
    ], 'Asia/Shanghai')

    expect(result.draft).toBeNull()
    expect(result.reason).toContain('跨越多个模块')
  })

  it('rejects stale dates, states, and no-op ordering', () => {
    const staleDate = buildAiReorderDraft(reorderSuggestion, [
      schedule({ id: 1, title: '低消耗任务', sortOrder: 10, startTime: '2026-09-11T01:00:00Z' }),
      schedule({ id: 2, title: '高消耗任务', sortOrder: 20 }),
    ], 'Asia/Shanghai')
    const completed = buildAiReorderDraft(reorderSuggestion, [
      schedule({ id: 1, title: '低消耗任务', sortOrder: 10, status: 'completed' }),
      schedule({ id: 2, title: '高消耗任务', sortOrder: 20 }),
    ], 'Asia/Shanghai')
    const noOp = buildAiReorderDraft(reorderSuggestion, [
      schedule({ id: 1, title: '低消耗任务', sortOrder: 20 }),
      schedule({ id: 2, title: '高消耗任务', sortOrder: 10 }),
    ], 'Asia/Shanghai')

    expect(staleDate.reason).toContain('日期已经变化')
    expect(completed.reason).toContain('状态已经变化')
    expect(noOp.reason).toContain('已经符合')
  })
})
