const LOCAL_DATETIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/

function zonedParts(value: number | Date, timezone: string) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(value))
  const part = (type: string) => Number(parts.find(item => item.type === type)?.value || 0)
  return {
    year: part('year'),
    month: part('month'),
    day: part('day'),
    hour: part('hour'),
    minute: part('minute'),
    second: part('second'),
  }
}

function timezoneOffset(timestamp: number, timezone: string) {
  const parts = zonedParts(timestamp, timezone)
  return Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second) - timestamp
}

/** Convert a datetime-local wall clock value in the profile timezone to UTC ISO. */
export function zonedDatetimeLocalToIso(value: string, timezone: string) {
  if (!value) return ''
  const match = LOCAL_DATETIME.exec(value)
  if (!match) {
    const fallback = new Date(value)
    return Number.isNaN(fallback.getTime()) ? '' : fallback.toISOString()
  }

  const [, year, month, day, hour, minute, second = '0'] = match
  const wallClock = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second))
  try {
    const firstPass = wallClock - timezoneOffset(wallClock, timezone)
    const resolved = wallClock - timezoneOffset(firstPass, timezone)
    const iso = new Date(resolved).toISOString()
    if (isoToZonedDatetimeLocal(iso, timezone) !== value.slice(0, 16)) {
      throw new RangeError('所选时间在当前时区不存在，请避开夏令时切换时段')
    }
    return iso
  } catch {
    const fallback = new Date(value)
    if (Number.isNaN(fallback.getTime())) return ''
    if (isoToZonedDatetimeLocal(fallback.toISOString(), timezone) !== value.slice(0, 16)) {
      throw new RangeError('所选时间在当前时区不存在，请避开夏令时切换时段')
    }
    return fallback.toISOString()
  }
}

/** Convert an ISO timestamp to a datetime-local value in the profile timezone. */
export function isoToZonedDatetimeLocal(value: string, timezone: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.slice(0, 16)
  try {
    const parts = zonedParts(date, timezone)
    const pad = (part: number) => String(part).padStart(2, '0')
    return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}T${pad(parts.hour)}:${pad(parts.minute)}`
  } catch {
    const pad = (part: number) => String(part).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
}
