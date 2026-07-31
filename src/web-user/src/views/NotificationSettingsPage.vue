<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { Bell, BellRing, CheckCircle2, Monitor } from 'lucide-vue-next'

const router = useRouter()
const store = useAppStore()

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
    </div>
  </section>
</template>
