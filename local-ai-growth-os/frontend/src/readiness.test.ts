import { describe, expect, it } from 'vitest'
import { diagnosticReady } from './readiness'
describe('diagnosticReady', () => {
  it('requires both a brand archive and a prompt case', () => {
    expect(diagnosticReady(0, 1)).toBe(false)
    expect(diagnosticReady(1, 0)).toBe(false)
    expect(diagnosticReady(1, 1)).toBe(true)
  })
})
