import { describe, expect, it } from 'vitest'
import { csvText, columnLabels, presetRange, queryPage, validRange, viewEndpoint } from './analytics'

describe('analytics filtering and exports', () => {
  it('rejects nonexistent, reversed, future and oversized date ranges', () => {
    const now = new Date('2026-09-23T01:00:00Z')
    expect(validRange(['2026-02-30', '2026-03-01'], now)).toBe(false)
    expect(validRange(['2026-09-24', '2026-09-25'], now)).toBe(false)
    expect(validRange(['2026-09-23', '2026-09-01'], now)).toBe(false)
    expect(validRange(['2020-01-01', '2026-09-23'], now)).toBe(false)
    expect(validRange(['2026-09-01', '2026-09-23'], now)).toBe(true)
  })
  it('uses UTC for presets even close to local midnight', () => {
    expect(presetRange(7, new Date('2026-09-23T01:00:00+08:00'))).toEqual(['2026-09-16', '2026-09-22'])
  })
  it('sanitizes CSV formulas, quotes and line breaks with Chinese headers', () => {
    const output = csvText([{ nickname: '=CMD()', reason: '"quote"\ntext', dateFrom: '2026-09-01' }], undefined, columnLabels)
    expect(output).toContain('"昵称","原因","范围开始"')
    expect(output).toContain("\"'=CMD()\"")
    expect(output).toContain('"""quote""\ntext"')
    expect(output.charCodeAt(0)).toBe(0xfeff)
  })
  it('validates URL pagination and resolves special endpoints', () => {
    for (const value of [0, -1, 'bad', '1.5', '1000001']) expect(queryPage(value)).toBe(1)
    expect(queryPage('4')).toBe(4)
    expect(viewEndpoint('ai/quota-alerts')).toBe('/admin/ai/quota-alerts')
    expect(viewEndpoint('ops/growth')).toBe('/admin/views/ops/growth')
  })
})
