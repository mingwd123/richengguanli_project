<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import PaginationBar from '../components/PaginationBar.vue'
import { formatTime } from '../utils/helpers'
import type { Notification } from '../types'

const store = useAppStore()
const router = useRouter()

const unreadCount = computed(() => store.today.unreadNotificationCount)

const notificationTypeLabels: Record<string, string> = {
  schedule_reminder: '日程提醒',
  team_task_assigned: '任务分配',
  team_task_status: '任务状态',
  team_member_joined: '成员加入',
  task_approval_requested: '任务审批',
  task_approved: '审批通过',
  task_approval_rejected: '审批未通过',
  task_unassigned: '任务待补位',
}

function notificationTypeLabel(type: string) {
  return notificationTypeLabels[type] || type
}

function reloadNotifications() {
  store.loadNotifications({ page: 1 })
}

function handleReadAll() {
  store.readAll()
}

function handleClick(n: Notification) {
  store.markNotificationRead(n)
  if (n.relatedType === 'schedule' && n.relatedId) {
    router.push(`/schedules/${n.relatedId}`)
    return
  }
  if (n.relatedType === 'team_task' && n.relatedId) {
    router.push(`/tasks/${n.relatedId}`)
    return
  }
  store.openNotificationDetail(n)
}

function closeDetail() {
  store.closeNotificationDetail()
}
</script>

<template>
  <section class="split-layout">
    <section class="notice-list">
      <div class="section-head">
        <h2>通知</h2>
        <div class="top-actions">
          <span class="muted">未读：{{ unreadCount }}</span>
          <button v-if="store.browserNoticePermission !== 'granted'" @click="store.requestBrowserNoticePermission">
            开启系统通知
          </button>
          <button v-if="unreadCount > 0" @click="handleReadAll">全部已读</button>
        </div>
      </div>

      <div class="search-bar">
        <select v-model="store.notificationPage.isRead" aria-label="通知状态" @change="reloadNotifications">
          <option value="">全部通知</option><option value="false">仅未读</option><option value="true">仅已读</option>
        </select>
        <select v-model="store.notificationPage.sort" aria-label="通知排序" @change="reloadNotifications">
          <option value="created_desc">最新优先</option><option value="created_asc">最早优先</option><option value="unread_first">未读优先</option>
        </select>
      </div>

      <article
        v-for="n in store.notifications"
        :key="n.id"
        :class="['notice-row', { read: n.isRead }]"
        style="cursor:pointer"
        @click="handleClick(n)"
      >
        <div>
          <strong>{{ n.title }}</strong>
          <small>{{ n.content }}</small>
          <div style="margin-top:6px">
            <span class="muted" style="font-size:12px">{{ formatTime(n.createdAt) }}</span>
          </div>
        </div>
        <span v-if="!n.isRead" class="tag blue">未读</span>
      </article>
      <p v-if="!store.notifications.length" class="hint" style="text-align:center;padding:40px 0">暂无通知</p>
      <PaginationBar
        :page="store.notificationPage.page"
        :size="store.notificationPage.size"
        :total="store.notificationPage.total"
        :loading="store.notificationPage.loading"
        @change="store.loadNotifications({ page: $event })"
        @resize="store.loadNotifications({ page: 1, size: $event })"
      />
    </section>

    <aside class="detail-panel">
      <h2>未读概览</h2>
      <strong style="font-size:30px;color:#2f80ed">{{ unreadCount }}</strong>
      <p class="muted">条未读通知</p>

      <!-- 通知详情弹窗 -->
      <div v-if="store.notificationDetail" class="modal-backdrop" @click.self="closeDetail">
        <section class="modal-panel">
          <div class="modal-head">
            <h2>通知详情</h2>
            <button class="modal-close" @click="closeDetail">✕</button>
          </div>
          <div style="display:grid;gap:12px">
            <div>
              <span class="muted">标题：</span>
              <strong>{{ store.notificationDetail.title }}</strong>
            </div>
            <div>
              <span class="muted">内容：</span>
              <p>{{ store.notificationDetail.content }}</p>
            </div>
            <div>
              <span class="muted">时间：</span>
              <span>{{ formatTime(store.notificationDetail.createdAt) }}</span>
            </div>
            <div>
              <span class="muted">类型：</span>
              <span>{{ notificationTypeLabel(store.notificationDetail.type) }}</span>
            </div>
            <div>
              <span class="muted">已读：</span>
              <span>{{ store.notificationDetail.isRead ? '是' : '否' }}</span>
            </div>
          </div>
        </section>
      </div>
    </aside>
  </section>
</template>
