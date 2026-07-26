<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '../stores/app'
import { formatTime, statusLabel } from '../utils/helpers'

const store = useAppStore()

const detail = computed(() => store.notificationDetail)

const typeLabel: Record<string, string> = {
  schedule_reminder: '日程提醒',
  team_task_assigned: '任务分配',
  team_task_status: '任务状态',
  team_member_joined: '成员加入',
}
</script>

<template>
  <div v-if="detail" class="modal-backdrop" @click.self="store.closeNotificationDetail()">
    <section class="modal-panel">
      <div class="modal-head">
        <h2>{{ detail.title }}</h2>
        <button class="modal-close" @click="store.closeNotificationDetail()">X</button>
      </div>
      <div>
        <p><strong>内容：</strong>{{ detail.content }}</p>
        <p><strong>类型：</strong>{{ typeLabel[detail.type] || detail.type }}</p>
        <p><strong>时间：</strong>{{ formatTime(detail.createdAt) }}</p>
        <p><strong>状态：</strong>{{ detail.isRead ? '已读' : '未读' }}</p>
      </div>
    </section>
  </div>
</template>
