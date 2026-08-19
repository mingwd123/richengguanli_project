import { invoke, isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { LogicalSize } from '@tauri-apps/api/dpi'
import {
  isPermissionGranted,
  onAction,
  requestPermission,
  sendNotification,
} from '@tauri-apps/plugin-notification'
import type { Notification } from '@web/types'
import {
  normalizeDesktopNavigationIntent,
  notificationNavigationIntent,
  type DesktopNavigationIntent,
  type StoredNotificationNavigation,
} from '../utils/desktopNavigation'

export const runningInTauri = isTauri()

const QUICK_TIMELINE_SIZE = { width: 468, height: 760 }
const WORKSPACE_MIN_SIZE = { width: 1024, height: 680 }
const QUICK_TIMELINE_MIN_SIZE = { width: 420, height: 600 }
const NATIVE_NAVIGATION_KEY = 'dayliane_desktop_notification_navigation'

type WindowSize = { width: number; height: number }

let workspaceSize: WindowSize | null = null
let workspaceWasMaximized = false

function currentWindow() {
  return runningInTauri ? getCurrentWindow() : null
}

export async function minimizeWindow() {
  await currentWindow()?.minimize()
}

export async function toggleMaximizeWindow() {
  await currentWindow()?.toggleMaximize()
}

export async function closeWindow() {
  await currentWindow()?.close()
}

export async function startWindowDrag() {
  await currentWindow()?.startDragging()
}

export async function setWindowAlwaysOnTop(enabled: boolean) {
  await currentWindow()?.setAlwaysOnTop(enabled)
}

/**
 * Switches between the full workspace and the narrow quick-timeline window.
 * Browser previews keep the same responsive layout without attempting native APIs.
 */
export async function setQuickTimelineWindow(enabled: boolean) {
  const window = currentWindow()
  if (!window) return

  if (enabled) {
    workspaceWasMaximized = await window.isMaximized()
    if (workspaceWasMaximized) await window.unmaximize()

    const [size, scaleFactor] = await Promise.all([window.innerSize(), window.scaleFactor()])
    const logicalSize = size.toLogical(scaleFactor)
    workspaceSize = { width: logicalSize.width, height: logicalSize.height }
    await window.setMaximizable(false)
    await window.setMinSize(new LogicalSize(QUICK_TIMELINE_MIN_SIZE.width, QUICK_TIMELINE_MIN_SIZE.height))
    await window.setSize(new LogicalSize(QUICK_TIMELINE_SIZE.width, QUICK_TIMELINE_SIZE.height))
    return
  }

  await window.setMaximizable(true)
  await window.setMinSize(new LogicalSize(WORKSPACE_MIN_SIZE.width, WORKSPACE_MIN_SIZE.height))
  if (workspaceSize) await window.setSize(new LogicalSize(workspaceSize.width, workspaceSize.height))
  if (workspaceWasMaximized) await window.maximize()
  workspaceSize = null
  workspaceWasMaximized = false
}

export async function nativeNotificationGranted() {
  if (!runningInTauri) return false
  return isPermissionGranted()
}

export async function requestNativeNotificationPermission() {
  if (!runningInTauri) return 'unavailable' as const
  if (await isPermissionGranted()) return 'granted' as const
  const permission = await requestPermission()
  return permission === 'granted' ? 'granted' as const : 'denied' as const
}

function nativeNotificationId(id: number) {
  const normalized = Math.abs(Math.trunc(id)) % 2_147_483_647
  return normalized || 1
}

function readNavigationMap() {
  try {
    return JSON.parse(localStorage.getItem(NATIVE_NAVIGATION_KEY) || '{}') as Record<string, StoredNotificationNavigation>
  } catch {
    return {}
  }
}

function storeNotificationNavigation(id: number, intent: StoredNotificationNavigation) {
  const entries = Object.entries({ ...readNavigationMap(), [String(id)]: intent }).slice(-200)
  localStorage.setItem(NATIVE_NAVIGATION_KEY, JSON.stringify(Object.fromEntries(entries)))
}

export async function sendNativeNotification(item: Notification) {
  if (!runningInTauri || !(await isPermissionGranted())) return false
  const id = nativeNotificationId(item.id)
  storeNotificationNavigation(id, notificationNavigationIntent(item))
  try {
    sendNotification({ id, title: item.title, body: item.content, group: 'dayliane' })
    return true
  } catch {
    return false
  }
}

export async function setPendingSurveyTray(pending: boolean) {
  if (!runningInTauri) return false
  try {
    await invoke('set_pending_survey_menu', { pending })
    return true
  } catch {
    return false
  }
}

export async function showMainWindow() {
  const window = currentWindow()
  if (!window) return
  await window.unminimize()
  await window.show()
  await window.setFocus()
}

export async function listenForDesktopNavigation(handler: (intent: DesktopNavigationIntent) => void | Promise<void>) {
  if (!runningInTauri) return async () => {}

  const cleanups: Array<() => void | Promise<void>> = []
  try {
    const unlisten = await listen<unknown>('desktop:navigate', event => {
      const intent = normalizeDesktopNavigationIntent(event.payload)
      if (intent) void handler(intent)
    })
    cleanups.push(unlisten)
  } catch {
    // Tray navigation remains optional when the platform event bridge is unavailable.
  }

  try {
    const listener = await onAction(options => {
      const intent = options.id == null ? null : readNavigationMap()[String(options.id)]
      if (intent) void showMainWindow().then(() => handler(intent))
    })
    cleanups.push(() => listener.unregister())
  } catch {
    // The in-app notification entry remains available without native action events.
  }

  try {
    const unlistenFocus = await currentWindow()?.onFocusChanged(event => {
      if (event.payload) void handler({ kind: 'refresh' })
    })
    if (unlistenFocus) cleanups.push(unlistenFocus)
  } catch {
    // Visibility and online listeners still refresh the application state.
  }

  return async () => {
    for (const cleanup of cleanups) await cleanup()
  }
}
