<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime } from '../utils/helpers'
import type { PageResult, Reminder } from '../types'

const store = useAppStore()
const router = useRouter()
const loading = ref(false)
const page = ref<PageResult<Reminder>>({ list: [], total: 0, page: 1, size: 20 })
const status = ref('')
const targetType = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(page.value.total / page.value.size)))
const statusText: Record<string, string> = { pending: '待发送', paused: '已暂停', sent: '已发送', cancelled: '已取消', failed: '发送失败' }
let requestVersion = 0

async function load(pageNumber = 1) {
  const version = ++requestVersion
  loading.value = true
  try {
    const params = new URLSearchParams({ page: String(pageNumber), size: String(page.value.size) })
    if (status.value) params.set('status', status.value)
    if (targetType.value) params.set('targetType', targetType.value)
    const data = await store.request<PageResult<Reminder>>(`/reminders/my?${params.toString()}`)
    if (version === requestVersion) page.value = data
  } catch (e: any) {
    if (version === requestVersion) store.notify(e.message || '加载提醒记录失败')
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

function openTarget(item: Reminder) {
  router.push(item.targetType === 'schedule' ? `/schedules/${item.targetId}` : `/tasks/${item.targetId}`)
}

watch([status, targetType], () => load(1))
onMounted(() => load())
</script>

<template>
  <section class="list-page">
    <section class="list-card">
      <div class="section-head">
        <div><h2>提醒记录</h2><p class="muted">查看待发送和历史提醒</p></div>
        <button :disabled="loading" @click="load(page.page)">刷新</button>
      </div>
      <div class="search-bar">
        <select v-model="status" aria-label="提醒状态">
          <option value="">全部状态</option><option value="pending">待发送</option><option value="paused">已暂停</option><option value="sent">已发送</option><option value="cancelled">已取消</option><option value="failed">发送失败</option>
        </select>
        <select v-model="targetType" aria-label="提醒来源">
          <option value="">全部来源</option><option value="schedule">个人日程</option><option value="team_task">团队任务</option>
        </select>
      </div>
      <div v-if="loading" class="hint" style="padding:36px;text-align:center">加载中...</div>
      <template v-else>
        <article v-for="item in page.list" :key="item.id" class="table-row reminder-row" @click="openTarget(item)">
          <div><strong>{{ item.targetTitle }}</strong><small>{{ item.targetType === 'schedule' ? '个人日程' : '团队任务' }} · {{ formatTime(item.remindAt) }}</small></div>
          <span :class="['tag', item.status === 'sent' ? 'blue' : ['pending', 'paused'].includes(item.status) ? 'warning' : 'danger']">{{ statusText[item.status] || item.status }}</span>
          <small>{{ item.sentAt ? `发送于 ${formatTime(item.sentAt)}` : `创建于 ${formatTime(item.createdAt)}` }}</small>
          <button @click.stop="openTarget(item)">查看</button>
        </article>
        <p v-if="!page.list.length" class="hint" style="padding:36px;text-align:center">暂无提醒记录</p>
      </template>
      <div v-if="page.total > page.size" class="pagination-bar">
        <button :disabled="page.page <= 1" @click="load(page.page - 1)">上一页</button>
        <span>第 {{ page.page }} / {{ totalPages }} 页</span>
        <button :disabled="page.page >= totalPages" @click="load(page.page + 1)">下一页</button>
      </div>
    </section>
  </section>
</template>
