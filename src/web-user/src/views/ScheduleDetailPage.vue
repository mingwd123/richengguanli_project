<script setup lang="ts">
import { computed, reactive, ref, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import FatiguePreviewInline from '../components/FatiguePreviewInline.vue'
import ScheduleLevelControl from '../components/ScheduleLevelControl.vue'
import { formatTime, getDisplayTimezone, timeTypeLabel, statusLabel, countdown, toDatetimeLocalInTimezone, toSchedulePayload, urgency } from '../utils/helpers'
import type { FatiguePreview, Schedule, TimeType } from '../types'

const props = defineProps<{ id: string }>()
const route = useRoute()
const router = useRouter()
const store = useAppStore()

const schedule = ref<Schedule | null>(null)
const loading = ref(false)
const editing = ref(false)
const editForm = reactive({ title: '', description: '', groupId: '', groupName: '', timeType: 'point_event' as TimeType, startTime: '', endTime: '', deadlineTime: '', remindAt: '', urgencyLevel: 3, fatigueLevel: 3 })
const initialRemindAt = ref('')
const editFatiguePreview = ref<FatiguePreview | null>(null)
const openedFromHome = computed(() => route.query.from === 'home')
const returnTarget = computed(() => openedFromHome.value ? '/' : '/schedules')
const returnLabel = computed(() => openedFromHome.value ? '返回首页' : '返回日程列表')
const userTimezone = computed(() => store.profile?.timezone || getDisplayTimezone())
const urgencyNames = ['不紧急', '较低', '普通', '紧急', '非常紧急']
const fatigueNames = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']

function toDatetimeLocal(value: string) {
  return toDatetimeLocalInTimezone(value, userTimezone.value)
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
  editForm.urgencyLevel = item.urgencyLevel || 3
  editForm.fatigueLevel = item.fatigueLevel || 3
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

let previewTimer: ReturnType<typeof setTimeout> | undefined
let previewVersion = 0

async function loadEditFatiguePreview() {
  const version = ++previewVersion
  const hasTime = editForm.timeType === 'deadline_task' ? Boolean(editForm.deadlineTime) : Boolean(editForm.startTime)
  if (!editing.value || !schedule.value || !hasTime || store.fatigueProfile?.fatigueTrackingEnabled === false) {
    editFatiguePreview.value = null
    return
  }
  try {
    const payload = toSchedulePayload(editForm, userTimezone.value)
    const result = await store.previewFatigue({ operation: 'edit', scheduleId: schedule.value.id, status: schedule.value.status, ...payload })
    if (version === previewVersion) editFatiguePreview.value = result
  } catch {
    if (version === previewVersion) editFatiguePreview.value = null
  }
}

watch([
  editing,
  () => editForm.timeType,
  () => editForm.startTime,
  () => editForm.endTime,
  () => editForm.deadlineTime,
  () => editForm.fatigueLevel,
], () => {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(loadEditFatiguePreview, 250)
})

async function saveEdit() {
  if (!schedule.value) return
  const ok = await store.updateSchedule(schedule.value.id, {
    ...editForm,
    reminderChanged: editForm.remindAt !== initialRemindAt.value
  })
  if (!ok) return
  editing.value = false
  await loadDetail()
}

async function handleAction(action: string) {
  if (!schedule.value) return
  if (await store.setScheduleStatus(schedule.value, action)) await loadDetail()
}

async function handleDelete() {
  if (!schedule.value) return
  if (!confirm('确认删除此日程？')) return
  if (await store.deleteSchedule(Number(schedule.value.id))) await router.push(returnTarget.value)
}

function goBack() {
  router.push(returnTarget.value)
}

function isPastPointEvent(item: Schedule) {
  return item.timeType === 'point_event' && new Date(item.startTime).getTime() < Date.now()
}

onMounted(loadDetail)
onUnmounted(() => clearTimeout(previewTimer))
</script>

<template>
  <section class="list-page">
    <div class="page-topbar" style="padding:0">
      <div>
        <button @click="goBack" style="border:0;background:transparent;padding:0;color:#2f80ed;font-weight:700">← {{ returnLabel }}</button>
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
        <div class="schedule-detail-levels">
          <span class="muted">紧急度：</span>
          <span class="tag warning">{{ schedule.urgencyLevel }} · {{ schedule.urgencyLabel }}</span>
          <span class="muted">预计疲劳：</span>
          <span class="tag danger">{{ schedule.fatigueLevel }} · {{ schedule.fatigueLabel }} · {{ schedule.fatigueWeight }} 点</span>
        </div>
        <div v-if="schedule.timeType === 'point_event'">
          <span class="muted">发生时间：</span>
          <span>{{ formatTime(schedule.startTime) }}</span>
          <span v-if="schedule.status === 'pending' && isPastPointEvent(schedule)" class="tag past" style="margin-left:8px">已过</span>
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
        <div v-if="schedule.completedAt">
          <span class="muted">完成快照：</span>
          <span>于 {{ formatTime(schedule.completedAt) }} 完成，实际疲劳 {{ schedule.completedFatigueLevel || '-' }} · {{ schedule.completedFatigueWeight || '-' }} 点</span>
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
          <div class="level-form-grid">
            <ScheduleLevelControl v-model="editForm.urgencyLevel" label="紧急度" :labels="urgencyNames" />
            <ScheduleLevelControl v-model="editForm.fatigueLevel" label="预计疲劳度" :labels="fatigueNames" :weights="store.fatigueProfile?.weights" />
          </div>
          <label v-if="editForm.timeType === 'point_event'">发生时间<input v-model="editForm.startTime" type="datetime-local" /></label>
          <label v-if="editForm.timeType === 'deadline_task'">截止时间<input v-model="editForm.deadlineTime" type="datetime-local" /></label>
          <template v-if="editForm.timeType === 'duration_task'">
            <label>开始时间<input v-model="editForm.startTime" type="datetime-local" /></label>
            <label>结束时间<input v-model="editForm.endTime" type="datetime-local" /></label>
          </template>
          <FatiguePreviewInline v-if="editFatiguePreview" :preview="editFatiguePreview" />
          <label>提醒时间<input v-model="editForm.remindAt" type="datetime-local" /></label>
          <small class="muted">保持原值不会替换提醒；清空后保存会取消未发送提醒。</small>
          <div class="form-actions"><button type="button" @click="editing = false">取消</button><button class="primary" :disabled="store.loading">保存修改</button></div>
        </form>
      </section>
    </div>
  </section>
</template>
