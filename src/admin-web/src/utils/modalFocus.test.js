import { describe, expect, it, vi } from 'vitest'
import {
  focusFirstModalElement,
  modalEscapeRequestsClose,
  modalFocusableElements,
  restoreModalTriggerFocus,
  trapModalTab,
} from './modalFocus'

function element(name, attributes = {}) {
  return {
    name,
    hidden: false,
    focus: vi.fn(),
    getAttribute: key => attributes[key] ?? null,
  }
}

function container(elements) {
  return {
    focus: vi.fn(),
    querySelectorAll: vi.fn(() => elements),
    contains: value => elements.includes(value),
  }
}

describe('modal focus helpers', () => {
  it('ignores hidden focus targets and focuses the first available control', () => {
    const hidden = element('hidden', { 'aria-hidden': 'true' })
    const first = element('first')
    const modal = container([hidden, first])
    expect(modalFocusableElements(modal)).toEqual([first])
    expect(focusFirstModalElement(modal)).toBe(first)
    expect(first.focus).toHaveBeenCalledOnce()
  })

  it('wraps Tab and Shift+Tab at both ends of a modal', () => {
    const first = element('first')
    const last = element('last')
    const modal = container([first, last])
    const forward = { key: 'Tab', shiftKey: false, preventDefault: vi.fn() }
    const backward = { key: 'Tab', shiftKey: true, preventDefault: vi.fn() }

    expect(trapModalTab(forward, modal, last)).toBe(true)
    expect(first.focus).toHaveBeenCalledOnce()
    expect(forward.preventDefault).toHaveBeenCalledOnce()
    expect(trapModalTab(backward, modal, first)).toBe(true)
    expect(last.focus).toHaveBeenCalledOnce()
    expect(backward.preventDefault).toHaveBeenCalledOnce()
  })

  it('keeps focus on the modal when saving disables every control', () => {
    const modal = container([])
    const event = { key: 'Tab', shiftKey: false, preventDefault: vi.fn() }
    expect(trapModalTab(event, modal, null)).toBe(true)
    expect(modal.focus).toHaveBeenCalledOnce()
  })

  it('allows Escape to close only while the modal is idle', () => {
    const idleEvent = { key: 'Escape', preventDefault: vi.fn() }
    const busyEvent = { key: 'Escape', preventDefault: vi.fn() }
    expect(modalEscapeRequestsClose(idleEvent, false)).toBe(true)
    expect(modalEscapeRequestsClose(busyEvent, true)).toBe(false)
    expect(idleEvent.preventDefault).toHaveBeenCalledOnce()
    expect(busyEvent.preventDefault).toHaveBeenCalledOnce()
  })

  it('restores focus to the original or redrawn trigger button', () => {
    const original = { isConnected: true, focus: vi.fn() }
    expect(restoreModalTriggerFocus(original, '7')).toBe(original)
    expect(original.focus).toHaveBeenCalledOnce()

    const redrawn = { dataset: { userEditId: '7' }, focus: vi.fn() }
    const root = { querySelectorAll: vi.fn(() => [redrawn]) }
    expect(restoreModalTriggerFocus({ isConnected: false }, '7', root)).toBe(redrawn)
    expect(redrawn.focus).toHaveBeenCalledOnce()
  })
})
