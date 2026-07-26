<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '../stores/app'
import { formatTime, statusLabel } from '../utils/helpers'

const store = useAppStore()

const unreadCount = computed(() => store.notifications.filter(n => !n.isRead).length)

function handleReadAll() {
  store.readAll()
}

function handleClick(n: any) {
  store.readNotification(n.id)
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
          <button v-if="unreadCount > 0" @click="handleReadAll">全部已读</button>
        </div>
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
              <span>{{ store.notificationDetail.type }}</span>
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
