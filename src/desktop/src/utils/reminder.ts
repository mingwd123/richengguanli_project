import { reminderTimeForOffset } from '@web/utils/helpers'
import { isoToZonedDatetimeLocal, zonedDatetimeLocalToIso } from './timezone'

export function reminderPresetLabel(minutes: number) {
  if (minutes % 1440 === 0) return `提前 ${minutes / 1440} 天`
  if (minutes % 60 === 0) return `提前 ${minutes / 60} 小时`
  return `提前 ${minutes} 分钟`
}

export function reminderLocalForOffset(baseTime: string, minutes: number, timezone: string) {
  if (!baseTime || !Number.isInteger(minutes) || minutes <= 0) return ''
  const baseIso = zonedDatetimeLocalToIso(baseTime, timezone)
  const reminderIso = reminderTimeForOffset(baseIso, minutes)
  return reminderIso ? isoToZonedDatetimeLocal(reminderIso, timezone) : ''
}

export function matchingReminderOffset(baseTime: string, remindAt: string, presets: number[], timezone: string) {
  if (!baseTime || !remindAt) return null
  try {
    const base = new Date(zonedDatetimeLocalToIso(baseTime, timezone)).getTime()
    const reminder = new Date(zonedDatetimeLocalToIso(remindAt, timezone)).getTime()
    const minutes = (base - reminder) / 60_000
    return Number.isInteger(minutes) && presets.includes(minutes) ? minutes : null
  } catch {
    return null
  }
}
