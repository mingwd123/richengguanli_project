<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ListTodo, Minus, Pin, Settings2, Square, X } from 'lucide-vue-next'
import { useDesktopShell } from '../stores/desktopShell'
import {
  closeWindow,
  minimizeWindow,
  startWindowDrag,
  toggleMaximizeWindow,
} from '../services/native'

const route = useRoute()
const shell = useDesktopShell()
const routeTitle = computed(() => shell.quickTimeline.value ? '快捷时间轴' : String(route.meta.title || '工作台'))

function handleDrag(event: MouseEvent) {
  if (event.buttons === 1) startWindowDrag()
}

function handleDoubleClick() {
  if (!shell.quickTimeline.value) toggleMaximizeWindow()
}
</script>

<template>
  <header class="desktop-titlebar" data-tauri-drag-region @mousedown="handleDrag" @dblclick="handleDoubleClick">
    <div class="desktop-titlebar-brand" data-tauri-drag-region>
      <span class="desktop-app-mark">D</span>
      <strong>Dayliane</strong>
      <span>{{ routeTitle }}</span>
    </div>
    <div class="desktop-titlebar-status" data-tauri-drag-region>
      <i :class="{ native: shell.runningInTauri.value }"></i>
      <span>{{ shell.runningInTauri.value ? 'Desktop' : 'Preview' }}</span>
    </div>
    <div class="desktop-window-actions" @mousedown.stop @dblclick.stop>
      <button type="button" :class="{ active: shell.quickTimeline.value }" :disabled="shell.quickTimeline.value && shell.quickTimelineExitGuard.value === 'blocked'" :title="shell.quickTimeline.value && shell.quickTimelineExitGuard.value === 'blocked' ? '正在创建子任务' : shell.quickTimeline.value ? '打开完整工作台' : '打开快捷时间轴'" :aria-label="shell.quickTimeline.value && shell.quickTimelineExitGuard.value === 'blocked' ? '正在创建子任务' : shell.quickTimeline.value ? '打开完整工作台' : '打开快捷时间轴'" @click="shell.toggleQuickTimeline">
        <ListTodo :size="15" />
      </button>
      <button type="button" :class="{ active: shell.alwaysOnTop.value }" title="窗口置顶" aria-label="窗口置顶" @click="shell.setAlwaysOnTop(!shell.alwaysOnTop.value)">
        <Pin :size="14" />
      </button>
      <button type="button" :class="{ active: shell.settingsOpen.value }" title="桌面偏好" aria-label="桌面偏好" @click="shell.toggleSettings">
        <Settings2 :size="15" />
      </button>
      <span class="desktop-action-divider"></span>
      <button type="button" title="最小化" aria-label="最小化" @click="minimizeWindow"><Minus :size="16" /></button>
      <button type="button" title="最大化" aria-label="最大化" :disabled="shell.quickTimeline.value" @click="toggleMaximizeWindow"><Square :size="13" /></button>
      <button type="button" class="desktop-close-button" title="隐藏到托盘" aria-label="隐藏到托盘" @click="closeWindow"><X :size="16" /></button>
    </div>
  </header>
</template>
