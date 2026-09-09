import { describe, expect, it } from 'vitest'
import { matchingReminderOffset, reminderLocalForOffset, reminderPresetLabel } from './reminder'

describe('web reminder shortcuts', () => {
  it('formats preset labels', () => {
    expect(reminderPresetLabel(15)).toBe('提前 15 分钟')
    expect(reminderPresetLabel(120)).toBe('提前 2 小时')
    expect(reminderPresetLabel(2880)).toBe('提前 2 天')
  })

  it('calculates reminder time in the profile timezone', () => {
    expect(reminderLocalForOffset('2026-08-04T10:30', 60, 'Asia/Shanghai')).toBe('2026-08-04T09:30')
  })

  it('keeps elapsed-time offsets correct across a DST jump', () => {
    expect(reminderLocalForOffset('2026-03-08T03:30', 60, 'America/New_York')).toBe('2026-03-08T01:30')
  })

  it('recognizes preset and custom reminder times', () => {
    const presets = [15, 30, 60, 1440]
    expect(matchingReminderOffset('2026-08-04T10:30', '2026-08-04T09:30', presets, 'Asia/Shanghai')).toBe(60)
    expect(matchingReminderOffset('2026-08-04T10:30', '2026-08-04T09:20', presets, 'Asia/Shanghai')).toBeNull()
  })
})
