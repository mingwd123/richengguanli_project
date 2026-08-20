import { reminderTimeForOffset, toDatetimeLocalInTimezone, zonedDateTimeToIso } from './helpers'

export function reminderPresetLabel(minutes: number) {
  if (minutes % 1440 === 0) return `提前 ${minutes / 1440} 天`
  if (minutes % 60 === 0) return `提前 ${minutes / 60} 小时`
  return `提前 ${minutes} 分钟`
}

export function reminderLocalForOffset(baseTime: string, minutes: number, timezone: string) {
  const reminderIso = reminderTimeForOffset(baseTime, minutes, timezone)
  return reminderIso ? toDatetimeLocalInTimezone(reminderIso, timezone) : ''
}

export function matchingReminderOffset(baseTime: string, remindAt: string, presets: number[], timezone: string) {
  if (!baseTime || !remindAt) return null
  try {
    const base = new Date(zonedDateTimeToIso(baseTime, timezone)).getTime()
    const reminder = new Date(zonedDateTimeToIso(remindAt, timezone)).getTime()
    const minutes = (base - reminder) / 60_000
    return Number.isInteger(minutes) && presets.includes(minutes) ? minutes : null
  } catch {
    return null
  }
}
