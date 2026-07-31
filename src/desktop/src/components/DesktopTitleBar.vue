<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Minus, Pin, Settings2, Square, X } from 'lucide-vue-next'
import { useDesktopShell } from '../stores/desktopShell'
import {
  closeWindow,
  minimizeWindow,
  startWindowDrag,
  toggleMaximizeWindow,
} from '../services/native'

const route = useRoute()
const shell = useDesktopShell()
const routeTitle = computed(() => String(route.meta.title || '工作台'))

function handleDrag(event: MouseEvent) {
  if (event.buttons === 1) startWindowDrag()
}
</script>

<template>
  <header class="desktop-titlebar" data-tauri-drag-region @mousedown="handleDrag" @dblclick="toggleMaximizeWindow">
    <div class="desktop-titlebar-brand" data-tauri-drag-region>
      <span class="desktop-app-mark">D</span>
      <strong>Dayliane</strong>
      <span>{{ routeTitle }}</span>
    </div>
    <div class="desktop-titlebar-status" data-tauri-drag-region>
      <i :class="{ native: shell.runningInTauri.value }"></i>
      <span>{{ shell.runningInTauri.value ? 'Desktop' : 'Preview' }}</span>
    </div>
    <div class="desktop-window-actions">
      <button :class="{ active: shell.alwaysOnTop.value }" title="窗口置顶" aria-label="窗口置顶" @click="shell.setAlwaysOnTop(!shell.alwaysOnTop.value)">
        <Pin :size="14" />
      </button>
      <button :class="{ active: shell.settingsOpen.value }" title="桌面偏好" aria-label="桌面偏好" @click="shell.toggleSettings">
        <Settings2 :size="15" />
      </button>
      <span class="desktop-action-divider"></span>
      <button title="最小化" aria-label="最小化" @click="minimizeWindow"><Minus :size="16" /></button>
      <button title="最大化" aria-label="最大化" @click="toggleMaximizeWindow"><Square :size="13" /></button>
      <button class="desktop-close-button" title="隐藏到托盘" aria-label="隐藏到托盘" @click="closeWindow"><X :size="16" /></button>
    </div>
  </header>
</template>
