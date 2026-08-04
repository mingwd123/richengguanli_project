import { describe, expect, it } from 'vitest'
import { isoToZonedDatetimeLocal, zonedDatetimeLocalToIso } from './timezone'

describe('desktop profile timezone conversion', () => {
  it('converts an Asia/Shanghai wall clock value to UTC', () => {
    expect(zonedDatetimeLocalToIso('2026-08-04T10:30', 'Asia/Shanghai')).toBe('2026-08-04T02:30:00.000Z')
  })

  it('accounts for daylight saving time', () => {
    expect(zonedDatetimeLocalToIso('2026-08-04T10:30', 'America/New_York')).toBe('2026-08-04T14:30:00.000Z')
  })

  it('rejects a wall clock time skipped by the DST spring transition', () => {
    expect(() => zonedDatetimeLocalToIso('2026-03-08T02:30', 'America/New_York')).toThrow('所选时间在当前时区不存在')
  })

  it('uses the first occurrence of a repeated DST fall wall clock time', () => {
    expect(zonedDatetimeLocalToIso('2026-11-01T01:30', 'America/New_York')).toBe('2026-11-01T05:30:00.000Z')
  })

  it('formats an ISO value for the profile timezone', () => {
    expect(isoToZonedDatetimeLocal('2026-08-04T02:30:00.000Z', 'Asia/Shanghai')).toBe('2026-08-04T10:30')
  })
})
