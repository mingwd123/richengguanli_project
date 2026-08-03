<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { Bell, BellRing, CheckCircle2, Clock3, Monitor, Plus, X } from 'lucide-vue-next'

const router = useRouter()
const store = useAppStore()
const presetValue = ref<number | null>(null)
const presetUnit = ref<'minutes' | 'hours' | 'days'>('minutes')

function reminderPresetLabel(minutes: number) {
  if (minutes % 1440 === 0) return `提前 ${minutes / 1440} 天`
  if (minutes % 60 === 0) return `提前 ${minutes / 60} 小时`
  return `提前 ${minutes} 分钟`
}

async function saveReminderPresets(next: number[]) {
  const previous = [...store.notificationPreferences.reminderPresetMinutes]
  store.notificationPreferences.reminderPresetMinutes = next
  const saved = await store.saveNotificationPreferences()
  if (!saved) store.notificationPreferences.reminderPresetMinutes = previous
}

async function addReminderPreset() {
  const value = Number(presetValue.value)
  const multiplier = presetUnit.value === 'days' ? 1440 : presetUnit.value === 'hours' ? 60 : 1
  const minutes = value * multiplier
  if (!Number.isInteger(minutes) || minutes <= 0 || minutes > 43_200) {
    store.notify('请输入 1 分钟到 30 天之间的整数')
    return
  }
  const current = store.notificationPreferences.reminderPresetMinutes
  if (current.includes(minutes)) return store.notify('该提醒时间已存在')
  if (current.length >= 8) return store.notify('最多保留 8 个快捷提醒')
  await saveReminderPresets([...current, minutes].sort((a, b) => a - b))
  presetValue.value = null
}

async function removeReminderPreset(minutes: number) {
  const current = store.notificationPreferences.reminderPresetMinutes
  if (current.length <= 1) return store.notify('请至少保留一个快捷提醒')
  await saveReminderPresets(current.filter(value => value !== minutes))
}

async function changeBrowserSetting() {
  if (store.notificationPreferences.browserEnabled && store.browserNoticePermission !== 'granted') {
    await store.requestBrowserNoticePermission()
    return
  }
  await store.saveNotificationPreferences()
}
</script>

<template>
  <section class="settings-card notification-settings">
    <div class="section-head">
      <div>
        <button class="plain-button" @click="router.push('/profile')">返回</button>
        <h2>通知设置</h2>
      </div>
      <Bell :size="20" />
    </div>

    <div class="settings-list">
      <label class="setting-switch">
        <span class="setting-icon"><Monitor :size="18" /></span>
        <span><strong>浏览器通知</strong><small>{{ store.browserNoticePermission === 'granted' ? '已授权' : '未授权' }}</small></span>
        <input v-model="store.notificationPreferences.browserEnabled" type="checkbox" @change="changeBrowserSetting" />
      </label>
      <label class="setting-switch">
        <span class="setting-icon"><BellRing :size="18" /></span>
        <span><strong>任务分配</strong><small>收到新的团队任务</small></span>
        <input v-model="store.notificationPreferences.taskAssignedEnabled" type="checkbox" @change="store.saveNotificationPreferences()" />
      </label>
      <label class="setting-switch">
        <span class="setting-icon"><CheckCircle2 :size="18" /></span>
        <span><strong>任务状态</strong><small>接受、拒绝、完成与取消</small></span>
        <input v-model="store.notificationPreferences.taskStatusEnabled" type="checkbox" @change="store.saveNotificationPreferences()" />
      </label>
      <label class="setting-switch">
        <span class="setting-icon"><Bell :size="18" /></span>
        <span><strong>到期提醒</strong><small>日程与团队任务提醒</small></span>
        <input v-model="store.notificationPreferences.reminderEnabled" type="checkbox" @change="store.saveNotificationPreferences()" />
      </label>
      <section class="reminder-preset-settings">
        <div class="reminder-preset-heading">
          <span class="setting-icon"><Clock3 :size="18" /></span>
          <span><strong>提醒快捷选项</strong><small>用于新建日程时快速计算提醒时间</small></span>
        </div>
        <div class="reminder-preset-list">
          <span v-for="minutes in store.notificationPreferences.reminderPresetMinutes" :key="minutes" class="reminder-preset-chip">
            {{ reminderPresetLabel(minutes) }}
            <button type="button" :title="`删除${reminderPresetLabel(minutes)}`" :aria-label="`删除${reminderPresetLabel(minutes)}`" @click="removeReminderPreset(minutes)"><X :size="14" /></button>
          </span>
        </div>
        <div class="reminder-preset-add">
          <input v-model.number="presetValue" type="number" min="1" max="43200" step="1" inputmode="numeric" aria-label="提醒提前量" placeholder="数值" @keyup.enter="addReminderPreset" />
          <select v-model="presetUnit" aria-label="提醒时间单位">
            <option value="minutes">分钟</option>
            <option value="hours">小时</option>
            <option value="days">天</option>
          </select>
          <button type="button" title="添加提醒时间" aria-label="添加提醒时间" @click="addReminderPreset"><Plus :size="17" /></button>
        </div>
      </section>
    </div>
  </section>
</template>
