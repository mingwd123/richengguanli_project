<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, timeTypeLabel, statusLabel, countdown, urgency } from '../utils/helpers'
import type { Schedule, TimeType } from '../types'

const props = defineProps<{ id: string }>()
const router = useRouter()
const store = useAppStore()

const schedule = ref<Schedule | null>(null)
const loading = ref(false)
const editing = ref(false)
const editForm = reactive({ title: '', description: '', groupId: '', groupName: '', timeType: 'point_event' as TimeType, startTime: '', endTime: '', deadlineTime: '', remindAt: '' })
const initialRemindAt = ref('')

function toDatetimeLocal(value: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.slice(0, 16)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function fillEditForm(item: Schedule) {
  editForm.title = item.title || ''
  editForm.description = item.description || ''
  editForm.groupId = String(item.groupId || '')
  editForm.groupName = item.groupName || ''
  editForm.timeType = item.timeType
  editForm.startTime = toDatetimeLocal(item.startTime)
  editForm.endTime = toDatetimeLocal(item.endTime)
  editForm.deadlineTime = toDatetimeLocal(item.deadlineTime)
  editForm.remindAt = toDatetimeLocal(item.pendingReminders?.[0]?.remindAt || '')
  initialRemindAt.value = editForm.remindAt
}

async function loadDetail() {
  loading.value = true
  try {
    const data = await store.request<Schedule>(`/schedules/${props.id}`)
    schedule.value = data
  } catch (e: any) {
    store.notify(e.message || '加载日程详情失败')
  } finally {
    loading.value = false
  }
}

function openEdit() {
  if (!schedule.value) return
  fillEditForm(schedule.value)
  editing.value = true
}

async function saveEdit() {
  if (!schedule.value) return
  const ok = await store.updateSchedule(schedule.value.id, { ...editForm, reminderChanged: editForm.remindAt !== initialRemindAt.value })
  if (!ok) return
  editing.value = false
  await loadDetail()
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
        <div v-if="schedule.description">
          <span class="muted">描述：</span>
          <span>{{ schedule.description }}</span>
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
        <div v-if="schedule.pendingReminders?.length">
          <span class="muted">下次提醒：</span>
          <span>{{ formatTime(schedule.pendingReminders[0].remindAt) }}</span>
        </div>
        <div>
          <span class="muted">创建时间：</span>
          <span>{{ formatTime(schedule.createdAt) }}</span>
        </div>
      </div>

      <div class="form-actions" style="margin-top:24px">
        <button class="primary" @click="openEdit">编辑</button>
        <button v-if="schedule.status === 'pending'" @click="handleAction('complete')">标记完成</button>
        <button v-if="schedule.status === 'completed'" @click="handleAction('uncomplete')">恢复</button>
        <button v-if="schedule.status === 'cancelled'" @click="handleAction('restore')">恢复</button>
        <button v-if="schedule.status === 'pending'" @click="handleAction('cancel')">取消日程</button>
        <button @click="handleDelete">删除</button>
      </div>
    </section>

    <div v-else class="hint" style="text-align:center;padding:60px 0">日程未找到</div>

    <div v-if="editing" class="modal-backdrop" @click.self="editing = false">
      <section class="modal-panel">
        <div class="modal-head">
          <h2>编辑日程</h2>
          <button class="modal-close" @click="editing = false">✕</button>
        </div>
        <form @submit.prevent="saveEdit">
          <label>标题<input v-model="editForm.title" /></label>
          <label>描述<textarea v-model="editForm.description" rows="3" placeholder="可选"></textarea></label>
          <label>模块<select v-model="editForm.groupId"><option v-for="group in store.taskGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select></label>
          <label>类型<select v-model="editForm.timeType"><option value="point_event">安排事项</option><option value="deadline_task">待办任务</option><option value="duration_task">时间段任务</option></select></label>
          <label v-if="editForm.timeType === 'point_event'">发生时间<input v-model="editForm.startTime" type="datetime-local" /></label>
          <label v-if="editForm.timeType === 'deadline_task'">截止时间<input v-model="editForm.deadlineTime" type="datetime-local" /></label>
          <template v-if="editForm.timeType === 'duration_task'">
            <label>开始时间<input v-model="editForm.startTime" type="datetime-local" /></label>
            <label>结束时间<input v-model="editForm.endTime" type="datetime-local" /></label>
          </template>
          <label>提醒时间<input v-model="editForm.remindAt" type="datetime-local" /></label>
          <small class="muted">保持原值不会替换提醒；清空后保存会取消未发送提醒。</small>
          <div class="form-actions"><button type="button" @click="editing = false">取消</button><button class="primary" :disabled="store.loading">保存修改</button></div>
        </form>
      </section>
    </div>
  </section>
</template>
