import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { LogicalSize } from '@tauri-apps/api/dpi'
import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
} from '@tauri-apps/plugin-notification'

export const runningInTauri = isTauri()

const QUICK_TIMELINE_SIZE = { width: 468, height: 760 }
const WORKSPACE_MIN_SIZE = { width: 1024, height: 680 }
const QUICK_TIMELINE_MIN_SIZE = { width: 420, height: 600 }

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
    await window.setMinSize(new LogicalSize(QUICK_TIMELINE_MIN_SIZE.width, QUICK_TIMELINE_MIN_SIZE.height))
    await window.setSize(new LogicalSize(QUICK_TIMELINE_SIZE.width, QUICK_TIMELINE_SIZE.height))
    return
  }

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

export async function sendNativeNotification(title: string, body: string) {
  if (!runningInTauri || !(await isPermissionGranted())) return false
  sendNotification({ title, body })
  return true
}
