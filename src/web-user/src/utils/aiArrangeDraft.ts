import type { AiArrangeSuggestion, Schedule } from '../types'
import { dateKeyInTimezone } from './helpers'

export interface AiReorderDraftItem {
  id: number
  title: string
}

export interface AiReorderDraft {
  date: string
  groupId: number
  groupName: string
  reason: string
  items: AiReorderDraftItem[]
}

export type AiSuggestionActionState =
  | { enabled: true; reason: '' }
  | { enabled: false; reason: string }

export type AiReorderDraftResult =
  | { draft: AiReorderDraft; reason: '' }
  | { draft: null; reason: string }

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

function validDateKey(value: unknown): value is string {
  if (typeof value !== 'string' || !DATE_PATTERN.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  const parsed = new Date(Date.UTC(year, month - 1, day))
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day
}

function reorderIds(suggestion: AiArrangeSuggestion) {
  if (!Array.isArray(suggestion.scheduleIds)) return null
  const ids = suggestion.scheduleIds.map(Number)
  if (ids.length < 2 || ids.some(id => !Number.isSafeInteger(id) || id <= 0)) return null
  if (new Set(ids).size !== ids.length) return null
  return ids
}

function plannedTime(schedule: Schedule) {
  return schedule.timeType === 'deadline_task' ? schedule.deadlineTime : schedule.startTime
}

export function aiSuggestionActionState(suggestion: AiArrangeSuggestion): AiSuggestionActionState {
  if (suggestion.type === 'reschedule') {
    const scheduleId = Number(suggestion.scheduleId)
    if (!Number.isSafeInteger(scheduleId) || scheduleId <= 0 || !validDateKey(suggestion.targetDate)) {
      return { enabled: false, reason: '建议缺少有效的日程或目标日期，请重新分析' }
    }
    return { enabled: true, reason: '' }
  }

  if (suggestion.type === 'reorder') {
    if (!validDateKey(suggestion.date) || !reorderIds(suggestion)) {
      return { enabled: false, reason: '建议缺少完整的日程顺序，请重新分析' }
    }
    return { enabled: true, reason: '' }
  }

  return {
    enabled: false,
    reason: '当前建议未包含子任务标题和安排日期，无法生成可确认的拆分草稿',
  }
}

export function buildAiReorderDraft(
  suggestion: AiArrangeSuggestion,
  schedules: Schedule[],
  timezone: string,
): AiReorderDraftResult {
  const state = aiSuggestionActionState(suggestion)
  if (!state.enabled || suggestion.type !== 'reorder') {
    return { draft: null, reason: state.reason || '这不是有效的顺序建议' }
  }

  const ids = reorderIds(suggestion)!
  const schedulesById = new Map(schedules.map(schedule => [schedule.id, schedule]))
  const orderedSchedules = ids.map(id => schedulesById.get(id))
  if (orderedSchedules.some(schedule => !schedule)) {
    return { draft: null, reason: '部分日程已不存在或不可访问，请重新分析' }
  }

  const rows = orderedSchedules as Schedule[]
  if (rows.some(schedule => schedule.status !== 'pending')) {
    return { draft: null, reason: '部分日程状态已经变化，请重新分析' }
  }

  const groupIds = new Set(rows.map(schedule => schedule.groupId))
  const groupId = rows[0].groupId
  if (groupIds.size !== 1 || !groupId || groupId <= 0) {
    return { draft: null, reason: '当前排序建议跨越多个模块，暂时无法作为一个顺序草稿保存' }
  }

  if (rows.some(schedule => dateKeyInTimezone(plannedTime(schedule), timezone) !== suggestion.date)) {
    return { draft: null, reason: '部分日程的安排日期已经变化，请重新分析' }
  }

  const currentIds = [...rows]
    .sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)
    .map(schedule => schedule.id)
  if (currentIds.every((id, index) => id === ids[index])) {
    return { draft: null, reason: '当前顺序已经符合这条建议，无需重复保存' }
  }

  return {
    draft: {
      date: suggestion.date!,
      groupId,
      groupName: rows[0].groupName || '未命名模块',
      reason: suggestion.reason || '',
      items: rows.map(schedule => ({ id: schedule.id, title: schedule.title })),
    },
    reason: '',
  }
}
