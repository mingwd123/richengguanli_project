import { computed, ref } from 'vue'
import {
  nativeNotificationGranted,
  requestNativeNotificationPermission,
  runningInTauri,
  setQuickTimelineWindow,
  setWindowAlwaysOnTop,
} from '../services/native'
import { clampOpacity } from '../utils/settings'

const OPACITY_KEY = 'dayliane_desktop_opacity'
const ALWAYS_ON_TOP_KEY = 'dayliane_desktop_always_on_top'
const NATIVE_NOTIFICATIONS_KEY = 'dayliane_desktop_native_notifications'
const QUICK_TIMELINE_KEY = 'dayliane_desktop_quick_timeline'
const storage = typeof localStorage === 'undefined' ? null : localStorage

type NotificationPermissionState = 'unknown' | 'granted' | 'denied' | 'unavailable'
type QuickTimelineExitGuard = 'none' | 'confirm' | 'blocked'

const settingsOpen = ref(false)
const opacity = ref(clampOpacity(Number(storage?.getItem(OPACITY_KEY) || 96)))
const alwaysOnTop = ref(storage?.getItem(ALWAYS_ON_TOP_KEY) === 'true')
const nativeNotificationsEnabled = ref(storage?.getItem(NATIVE_NOTIFICATIONS_KEY) !== 'false')
const quickTimeline = ref(storage?.getItem(QUICK_TIMELINE_KEY) === 'true')
const quickTimelineExitGuard = ref<QuickTimelineExitGuard>('none')
const quickTimelineExitMessage = ref('')
const notificationPermission = ref<NotificationPermissionState>(runningInTauri ? 'unknown' : 'unavailable')
let initialized = false

function applyOpacity() {
  document.documentElement.style.setProperty('--desktop-opacity', `${opacity.value}%`)
  document.documentElement.style.setProperty('--desktop-opacity-ratio', String(opacity.value / 100))
}

export function useDesktopShell() {
  async function initialize() {
    if (initialized) return
    initialized = true
    document.documentElement.classList.add('desktop-environment')
    applyOpacity()
    if (runningInTauri) {
      await setWindowAlwaysOnTop(alwaysOnTop.value)
      if (quickTimeline.value) {
        try {
          await setQuickTimelineWindow(true)
        } catch {
          // Keep the remembered panel layout available even when native resizing fails.
        }
      }
      notificationPermission.value = await nativeNotificationGranted() ? 'granted' : 'unknown'
    }
  }

  function toggleSettings() {
    settingsOpen.value = !settingsOpen.value
  }

  function closeSettings() {
    settingsOpen.value = false
  }

  function setOpacity(value: number) {
    opacity.value = clampOpacity(value)
    storage?.setItem(OPACITY_KEY, String(opacity.value))
    applyOpacity()
  }

  async function setAlwaysOnTop(enabled: boolean) {
    alwaysOnTop.value = enabled
    storage?.setItem(ALWAYS_ON_TOP_KEY, String(enabled))
    await setWindowAlwaysOnTop(enabled)
  }

  async function setQuickTimeline(enabled: boolean) {
    if (quickTimeline.value === enabled) return
    if (!enabled && quickTimelineExitGuard.value === 'blocked') return false
    if (!enabled && quickTimelineExitGuard.value === 'confirm'
      && !window.confirm(quickTimelineExitMessage.value || '当前内容尚未保存，仍要切换到完整工作台吗？')) return false
    quickTimeline.value = enabled
    storage?.setItem(QUICK_TIMELINE_KEY, String(enabled))
    if (enabled) settingsOpen.value = false
    try {
      await setQuickTimelineWindow(enabled)
    } catch {
      // The panel remains usable when a platform denies native window resizing.
    }
    return true
  }

  function toggleQuickTimeline() {
    return setQuickTimeline(!quickTimeline.value)
  }

  function setQuickTimelineExitGuard(mode: QuickTimelineExitGuard, message = '') {
    quickTimelineExitGuard.value = mode
    quickTimelineExitMessage.value = message
  }

  async function requestNotificationPermission() {
    notificationPermission.value = await requestNativeNotificationPermission()
    if (notificationPermission.value === 'granted') setNativeNotificationsEnabled(true)
    return notificationPermission.value
  }

  function setNativeNotificationsEnabled(enabled: boolean) {
    nativeNotificationsEnabled.value = enabled
    storage?.setItem(NATIVE_NOTIFICATIONS_KEY, String(enabled))
  }

  return {
    settingsOpen,
    opacity,
    alwaysOnTop,
    nativeNotificationsEnabled,
    quickTimeline,
    quickTimelineExitGuard,
    notificationPermission,
    runningInTauri: computed(() => runningInTauri),
    initialize,
    toggleSettings,
    closeSettings,
    setOpacity,
    setAlwaysOnTop,
    setQuickTimeline,
    toggleQuickTimeline,
    setQuickTimelineExitGuard,
    requestNotificationPermission,
    setNativeNotificationsEnabled,
  }
}
