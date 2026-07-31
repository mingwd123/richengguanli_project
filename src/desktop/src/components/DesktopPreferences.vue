<script setup lang="ts">
import { BellRing, Layers2, Pin, X } from 'lucide-vue-next'
import { useDesktopShell } from '../stores/desktopShell'

const shell = useDesktopShell()

async function enableNotifications() {
  await shell.requestNotificationPermission()
}
</script>

<template>
  <div v-if="shell.settingsOpen.value" class="desktop-preferences-backdrop" @click="shell.closeSettings"></div>
  <aside v-if="shell.settingsOpen.value" class="desktop-preferences" aria-label="桌面偏好">
    <header>
      <div>
        <p>DESKTOP</p>
        <h2>桌面偏好</h2>
      </div>
      <button class="desktop-preference-close" title="关闭" aria-label="关闭" @click="shell.closeSettings"><X :size="16" /></button>
    </header>

    <section class="desktop-preference-row">
      <span class="desktop-preference-icon"><Pin :size="17" /></span>
      <div><strong>窗口置顶</strong><small>保持在其他窗口上方</small></div>
      <label class="desktop-switch">
        <input :checked="shell.alwaysOnTop.value" type="checkbox" @change="shell.setAlwaysOnTop(($event.target as HTMLInputElement).checked)" />
        <span></span>
      </label>
    </section>

    <section class="desktop-preference-slider">
      <div class="desktop-preference-row compact">
        <span class="desktop-preference-icon"><Layers2 :size="17" /></span>
        <div><strong>界面透明度</strong><small>{{ shell.opacity.value }}%</small></div>
      </div>
      <input :value="shell.opacity.value" type="range" min="78" max="100" step="1" aria-label="界面透明度" @input="shell.setOpacity(Number(($event.target as HTMLInputElement).value))" />
    </section>

    <section class="desktop-preference-row">
      <span class="desktop-preference-icon coral"><BellRing :size="17" /></span>
      <div>
        <strong>原生通知</strong>
        <small>{{ shell.notificationPermission.value === 'granted' ? '已授权' : shell.notificationPermission.value === 'denied' ? '已拒绝' : shell.notificationPermission.value === 'unavailable' ? '仅原生应用可用' : '等待授权' }}</small>
      </div>
      <button v-if="shell.notificationPermission.value !== 'granted' && shell.runningInTauri.value" class="desktop-permission-button" @click="enableNotifications">授权</button>
      <label v-else-if="shell.notificationPermission.value === 'granted'" class="desktop-switch">
        <input :checked="shell.nativeNotificationsEnabled.value" type="checkbox" @change="shell.setNativeNotificationsEnabled(($event.target as HTMLInputElement).checked)" />
        <span></span>
      </label>
    </section>
  </aside>
</template>
