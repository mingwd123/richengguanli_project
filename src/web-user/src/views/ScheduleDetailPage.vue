<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, primaryTime, timeTypeLabel, statusLabel, countdown, urgency } from '../utils/helpers'
import type { Schedule } from '../types'

const props = defineProps<{ id: string }>()
const router = useRouter()
const store = useAppStore()

const schedule = ref<Schedule | null>(null)
const loading = ref(false)

async function loadDetail() {
  loading.value = true
  try {
    const local = store.schedules.find(s => String(s.id) === props.id)
    if (local) {
      schedule.value = local
    } else {
      const data = await store.request<Schedule>(`/schedules/${props.id}`)
      schedule.value = data
    }
  } catch (e: any) {
    store.notify(e.message || '加载日程详情失败')
  } finally {
    loading.value = false
  }
}

function handleAction(action: string) {
  if (!schedule.value) return
  store.setScheduleStatus(schedule.value, action).then(() => loadDetail())
}

function handleDelete() {
  if (!schedule.value) return
  if (!confirm('确认删除此日程？')) return
  store.deleteSchedule(Number(schedule.value.id)).then(() => {
    router.push('/schedules')
  })
}

function goBack() {
  router.push('/schedules')
}

onMounted(loadDetail)
</script>

<template>
  <section class="list-page">
    <div class="page-topbar" style="padding:0">
      <div>
        <button @click="goBack" style="border:0;background:transparent;padding:0;color:#2f80ed;font-weight:700">← 返回日程列表</button>
      </div>
    </div>

    <div v-if="loading" class="hint" style="text-align:center;padding:60px 0">加载中...</div>

    <section v-else-if="schedule" class="form-card" style="margin-top:16px">
      <h2>{{ schedule.title }}</h2>
      <div style="display:grid;gap:16px;margin-top:20px">
        <div>
          <span class="muted">模块：</span>
          <span>{{ schedule.groupName || '未分组' }}</span>
        </div>
        <div>
          <span class="muted">类型：</span>
          <span :class="['tag', schedule.timeType === 'point_event' ? 'blue' : schedule.timeType === 'duration_task' ? 'blue' : 'warning']">{{ timeTypeLabel(schedule.timeType) }}</span>
        </div>
        <div v-if="schedule.timeType === 'point_event'">
          <span class="muted">发生时间：</span>
          <span>{{ formatTime(schedule.startTime) }}</span>
        </div>
        <div v-if="schedule.timeType === 'deadline_task'">
          <span class="muted">截止时间：</span>
          <span>{{ formatTime(schedule.deadlineTime) }}</span>
          <span v-if="schedule.status === 'pending'" :class="['tag', urgency(schedule.deadlineTime)]" style="margin-left:8px">{{ countdown(schedule.deadlineTime) }}</span>
        </div>
        <template v-if="schedule.timeType === 'duration_task'">
          <div>
            <span class="muted">开始时间：</span>
            <span>{{ formatTime(schedule.startTime) }}</span>
          </div>
          <div>
            <span class="muted">结束时间：</span>
            <span>{{ formatTime(schedule.endTime) }}</span>
            <span v-if="schedule.status === 'pending'" :class="['tag', urgency(schedule.endTime)]" style="margin-left:8px">{{ countdown(schedule.endTime) }}</span>
          </div>
        </template>
        <div>
          <span class="muted">状态：</span>
          <span :class="['tag', schedule.status === 'completed' ? 'blue' : schedule.status === 'cancelled' ? 'danger' : 'warning']">{{ statusLabel(schedule.status) }}</span>
        </div>
        <div>
          <span class="muted">提醒数：</span>
          <span>{{ schedule.reminderCount }}</span>
        </div>
        <div>
          <span class="muted">创建时间：</span>
          <span>{{ formatTime(schedule.createdAt) }}</span>
        </div>
      </div>

      <div class="form-actions" style="margin-top:24px">
        <button v-if="schedule.status === 'pending'" class="primary" @click="handleAction('complete')">标记完成</button>
        <button v-if="schedule.status === 'completed'" @click="handleAction('uncomplete')">恢复</button>
        <button v-if="schedule.status === 'pending'" @click="handleAction('cancel')">取消日程</button>
        <button @click="handleDelete">删除</button>
      </div>
    </section>

    <div v-else class="hint" style="text-align:center;padding:60px 0">日程未找到</div>
  </section>
</template>
