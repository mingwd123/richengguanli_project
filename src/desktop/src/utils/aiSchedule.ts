import type { TaskGroup, TimeType } from '@web/types'
import { isoToZonedDatetimeLocal } from './timezone'

export interface AiScheduleDraft {
  title: string
  groupName: string
  timeType: string
  startTime: string
  endTime: string
  deadlineTime: string
  remindAt: string
  description: string
}

const TIME_TYPES: TimeType[] = ['point_event', 'deadline_task', 'duration_task']
const LOCAL_DATETIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

export function normalizeAiScheduleDraft(value: unknown): AiScheduleDraft {
  const source = value && typeof value === 'object' ? value as Record<string, unknown> : {}
  const text = (key: keyof AiScheduleDraft) => String(source[key] || '').trim()
  return {
    title: text('title'),
    groupName: text('groupName'),
    timeType: text('timeType'),
    startTime: text('startTime'),
    endTime: text('endTime'),
    deadlineTime: text('deadlineTime'),
    remindAt: text('remindAt'),
    description: text('description'),
  }
}

export function resolveAiTimeType(draft: AiScheduleDraft, fallback: TimeType): TimeType {
  if (draft.startTime && draft.endTime) return 'duration_task'
  if (draft.deadlineTime) return 'deadline_task'
  return TIME_TYPES.includes(draft.timeType as TimeType) ? draft.timeType as TimeType : fallback
}

export function normalizeAiDateTime(value: string, timezone: string) {
  const text = String(value || '').trim().replace(' ', 'T')
  if (!text) return ''
  if (LOCAL_DATETIME.test(text) && !/[zZ]|[+-]\d{2}:?\d{2}$/.test(text)) return text.slice(0, 16)
  const date = new Date(text)
  return Number.isNaN(date.getTime()) ? '' : isoToZonedDatetimeLocal(date.toISOString(), timezone)
}

export function inferAiGroupId(groups: TaskGroup[], draft: AiScheduleDraft, sourceText: string, fallback = '') {
  const explicitGroupName = sourceText.match(/(?:放到?|归到|归入|分到|放进|放入)([^，。,\.\s]+)/)?.[1]?.trim()
  const explicitGroup = explicitGroupName && groups.find(group => group.name === explicitGroupName)
  if (explicitGroup) return String(explicitGroup.id)

  const aiGroup = draft.groupName && groups.find(group => group.name === draft.groupName)
  if (aiGroup) return String(aiGroup.id)

  const text = `${sourceText} ${draft.title}`
  const keywordRules = [
    { name: '学习', keywords: ['学习', '图书馆', '读书', '上课', '考试', '复习', '作业'] },
    { name: '工作', keywords: ['工作', '开会', '会议', '项目', '汇报', '客户'] },
    { name: '运动', keywords: ['运动', '健身', '跑步', '游泳', '训练'] },
    { name: '生活', keywords: ['生活', '吃饭', '购物', '家务', '买菜'] },
  ]
  for (const rule of keywordRules) {
    if (!rule.keywords.some(keyword => text.includes(keyword))) continue
    const group = groups.find(item => item.name === rule.name)
    if (group) return String(group.id)
  }
  return fallback
}
