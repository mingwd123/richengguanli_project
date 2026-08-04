import { describe, expect, it } from 'vitest'
import type { TaskGroup } from '@web/types'
import { inferAiGroupId, normalizeAiDateTime, normalizeAiScheduleDraft, resolveAiTimeType } from './aiSchedule'

const groups = [
  { id: 1, name: '工作', scope: 'personal', sortOrder: 10 },
  { id: 2, name: '学习', scope: 'personal', sortOrder: 20 },
] as TaskGroup[]

describe('desktop AI schedule parsing helpers', () => {
  it('normalizes incomplete draft responses', () => {
    expect(normalizeAiScheduleDraft({ title: '开会', deadlineTime: null })).toEqual({
      title: '开会', groupName: '', timeType: '', startTime: '', endTime: '', deadlineTime: '', remindAt: '', description: '',
    })
  })

  it('prioritizes duration and deadline fields when resolving the mode', () => {
    const base = normalizeAiScheduleDraft({ timeType: 'point_event' })
    expect(resolveAiTimeType({ ...base, startTime: '2026-08-05T09:00', endTime: '2026-08-05T10:00' }, 'point_event')).toBe('duration_task')
    expect(resolveAiTimeType({ ...base, deadlineTime: '2026-08-05T18:00' }, 'point_event')).toBe('deadline_task')
  })

  it('matches explicit and inferred groups using the web rules', () => {
    const draft = normalizeAiScheduleDraft({ title: '准备汇报', groupName: '学习' })
    expect(inferAiGroupId(groups, draft, '准备汇报，放到工作')).toBe('1')
    expect(inferAiGroupId(groups, draft, '准备汇报')).toBe('2')
    expect(inferAiGroupId(groups, normalizeAiScheduleDraft({ title: '项目会议' }), '项目会议')).toBe('1')
  })

  it('keeps local AI times and converts zoned ISO values', () => {
    expect(normalizeAiDateTime('2026-08-05T09:30', 'Asia/Shanghai')).toBe('2026-08-05T09:30')
    expect(normalizeAiDateTime('2026-08-05T01:30:00Z', 'Asia/Shanghai')).toBe('2026-08-05T09:30')
  })
})
