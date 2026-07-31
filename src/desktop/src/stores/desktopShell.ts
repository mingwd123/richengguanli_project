import { computed, ref } from 'vue'
import {
  nativeNotificationGranted,
  requestNativeNotificationPermission,
  runningInTauri,
  setWindowAlwaysOnTop,
} from '../services/native'
import { clampOpacity } from '../utils/settings'

const OPACITY_KEY = 'dayliane_desktop_opacity'
const ALWAYS_ON_TOP_KEY = 'dayliane_desktop_always_on_top'
const NATIVE_NOTIFICATIONS_KEY = 'dayliane_desktop_native_notifications'
const storage = typeof localStorage === 'undefined' ? null : localStorage

type NotificationPermissionState = 'unknown' | 'granted' | 'denied' | 'unavailable'

const settingsOpen = ref(false)
const opacity = ref(clampOpacity(Number(storage?.getItem(OPACITY_KEY) || 96)))
const alwaysOnTop = ref(storage?.getItem(ALWAYS_ON_TOP_KEY) === 'true')
const nativeNotificationsEnabled = ref(storage?.getItem(NATIVE_NOTIFICATIONS_KEY) !== 'false')
const notificationPermission = ref<NotificationPermissionState>(runningInTauri ? 'unknown' : 'unavailable')
let initialized = false

function applyOpacity() {
  document.documentElement.style.setProperty('--desktop-opacity', `${opacity.value}%`)
}

export function useDesktopShell() {
  async function initialize() {
    if (initialized) return
    initialized = true
    document.documentElement.classList.add('desktop-environment')
    applyOpacity()
    if (runningInTauri) {
      await setWindowAlwaysOnTop(alwaysOnTop.value)
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
    notificationPermission,
    runningInTauri: computed(() => runningInTauri),
    initialize,
    toggleSettings,
    closeSettings,
    setOpacity,
    setAlwaysOnTop,
    requestNotificationPermission,
    setNativeNotificationsEnabled,
  }
}
