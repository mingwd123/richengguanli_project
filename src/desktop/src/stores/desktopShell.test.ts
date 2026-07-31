import { describe, expect, it } from 'vitest'
import { clampOpacity } from '../utils/settings'

describe('desktop shell settings', () => {
  it('keeps opacity inside the supported range', () => {
    expect(clampOpacity(40)).toBe(78)
    expect(clampOpacity(94.6)).toBe(95)
    expect(clampOpacity(120)).toBe(100)
  })

  it('falls back for invalid opacity values', () => {
    expect(clampOpacity(Number.NaN)).toBe(96)
  })
})
