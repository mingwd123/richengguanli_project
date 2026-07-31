import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
} from '@tauri-apps/plugin-notification'

export const runningInTauri = isTauri()

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
