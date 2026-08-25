const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

export function modalFocusableElements(container) {
  if (!container?.querySelectorAll) return []
  return Array.from(container.querySelectorAll(FOCUSABLE_SELECTOR)).filter(element => (
    !element.hidden && element.getAttribute?.('aria-hidden') !== 'true'
  ))
}

export function focusFirstModalElement(container) {
  const target = modalFocusableElements(container)[0] || container
  target?.focus?.()
  return target || null
}

export function trapModalTab(event, container, activeElement) {
  if (event?.key !== 'Tab' || !container) return false
  const focusable = modalFocusableElements(container)
  const current = activeElement ?? (typeof document === 'undefined' ? null : document.activeElement)

  if (!focusable.length) {
    event.preventDefault?.()
    container.focus?.()
    return true
  }

  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const currentIsInside = container.contains?.(current) === true
  if (event.shiftKey && (!currentIsInside || current === first)) {
    event.preventDefault?.()
    last.focus?.()
    return true
  }
  if (!event.shiftKey && (!currentIsInside || current === last)) {
    event.preventDefault?.()
    first.focus?.()
    return true
  }
  return false
}

export function modalEscapeRequestsClose(event, busy = false) {
  if (event?.key !== 'Escape') return false
  event.preventDefault?.()
  return !busy
}

export function restoreModalTriggerFocus(trigger, triggerId, root) {
  let target = trigger?.isConnected ? trigger : null
  const searchRoot = root ?? (typeof document === 'undefined' ? null : document)
  if (!target && triggerId && searchRoot?.querySelectorAll) {
    target = Array.from(searchRoot.querySelectorAll('[data-user-edit-id]'))
      .find(element => element.dataset?.userEditId === String(triggerId)) || null
  }
  target?.focus?.()
  return target
}
