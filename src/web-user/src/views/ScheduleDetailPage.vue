<script setup lang="ts">
import { computed, reactive, ref, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import FatiguePreviewInline from '../components/FatiguePreviewInline.vue'
import ScheduleLevelControl from '../components/ScheduleLevelControl.vue'
import ReminderShortcutPicker from '../components/ReminderShortcutPicker.vue'
import RepeatRuleEditor from '../components/RepeatRuleEditor.vue'
import ScheduleProgressPanel from '../components/ScheduleProgressPanel.vue'
import { formatTime, getDisplayTimezone, timeTypeLabel, statusLabel, countdown, toDatetimeLocalInTimezone, toSchedulePayload, urgency, describeRrule, formatProgressPercent } from '../utils/helpers'
import type { FatiguePreview, Schedule, TimeType } from '../types'

const props = defineProps<{ id: string }>()
const route = useRoute()
const router = useRouter()
const store = useAppStore()

const schedule = ref<Schedule | null>(null)
const loading = ref(false)
const editing = ref(false)
const editScope = ref<'occurrence' | 'series'>('occurrence')
const editForm = reactive({ title: '', description: '', groupId: '', groupName: '', timeType: 'point_event' as TimeType, startTime: '', endTime: '', deadlineTime: '', remindAt: '', urgencyLevel: 3, fatigueLevel: 3, rrule: '', excludedDates: [] as string[], progressTrackingEnabled: false })
const initialRemindAt = ref('')
const editFatiguePreview = ref<FatiguePreview | null>(null)
const openedFromHome = computed(() => route.query.from === 'home')
const returnTarget = computed(() => openedFromHome.value ? '/' : '/schedules')
const returnLabel = computed(() => openedFromHome.value ? '返回首页' : '返回日程列表')
const userTimezone = computed(() => store.profile?.timezone || getDisplayTimezone())
const reminderPresets = computed(() => store.notificationPreferences.reminderPresetMinutes || [])
const reminderBaseTime = computed(() => editForm.timeType === 'deadline_task' ? editForm.deadlineTime : editForm.startTime)
const reminderBaseLabel = computed(() => editForm.timeType === 'deadline_task' ? '截止时间' : '开始时间')
const isRecurring = computed(() => Boolean(schedule.value?.seriesId || schedule.value?.rrule))
// 每日进度与点事件、重复系列互斥；已有进度时必须先清零记录才能关闭（后端同样校验）。
const editProgressToggleVisible = computed(() => editForm.progressTrackingEnabled
  || ((editForm.timeType === 'deadline_task' || editForm.timeType === 'duration_task') && !editForm.rrule && !isRecurring.value))
const editProgressBlockedByType = computed(() => editForm.progressTrackingEnabled && editForm.timeType === 'point_event')
const repeatLabel = computed(() => describeRrule(schedule.value?.rrule))
const urgencyNames = ['不紧急', '较低', '普通', '紧急', '非常紧急']
const fatigueNames = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']

/* ---------- 每日进度（阶段 1A）：面板由共用组件负责，这里只读共享状态做头部展示 ---------- */
const progressPercent = computed(() => Number(store.scheduleProgress?.progressPercent ?? schedule.value?.progressPercent ?? 0))
const progressPercentText = computed(() => `${formatProgressPercent(progressPercent.value)}`)
const progressCompletedDate = computed(() => store.scheduleProgress?.completedDate || schedule.value?.progressCompletedDate || '')

function progressLoadTotal() {
  return store.scheduleProgress?.totalCompletedLoad ?? 0
}

/** 进度面板在提交/修正后触发，用于刷新日程详情。 */
async function onProgressUpdated() {
  await loadDetail()
}

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
  editForm.rrule = item.rrule || ''
  editForm.excludedDates = [...(item.excludedDates || [])]
  // 必须回填，否则保存编辑时会把 progressTrackingEnabled 当成 false 发出去。
  editForm.progressTrackingEnabled = Boolean(item.progressTrackingEnabled)
  initialRemindAt.value = editForm.remindAt
}

async function loadDetail() {
  loading.value = true
  try {
    const data = await store.request<Schedule>(`/schedules/${props.id}`)
    schedule.value = data
    // P11：进度数据由共用进度面板负责加载；非进度任务立即清空，避免残留上一个任务的状态。
    if (!data.progressTrackingEnabled) store.resetScheduleProgress()
    const arrangeTo = typeof route.query.arrangeTo === 'string' ? route.query.arrangeTo : ''
    if (arrangeTo) {
      applyArrangedDate(arrangeTo)
      // Consume the one-shot AI draft marker so later reloads do not reopen it.
      const remainingQuery = { ...route.query }
      delete remainingQuery.arrangeTo
      await router.replace({ query: remainingQuery })
      store.notify('已按建议将日期调整为 ' + arrangeTo + '，请确认后保存')
    }
  } catch (e: any) {
    store.notify(e.message || '加载日程详情失败')
  } finally {
    loading.value = false
  }
}

function openEdit() {
  if (!schedule.value) return
  fillEditForm(schedule.value)
  editScope.value = 'occurrence'
  editing.value = true
}

function replaceDatePart(datetimeLocal: string, date: string) {
  if (!datetimeLocal || !date) return datetimeLocal
  const idx = datetimeLocal.indexOf('T')
  if (idx < 0) return `${date}T${editForm.startTime ? datetimeLocal.slice(11) : '09:00'}`
  return `${date}${datetimeLocal.slice(idx)}`
}

function applyArrangedDate(date: string) {
  if (!schedule.value) return
  fillEditForm(schedule.value)
  if (editForm.timeType === 'duration_task') {
    editForm.startTime = replaceDatePart(editForm.startTime, date)
    editForm.endTime = replaceDatePart(editForm.endTime, date)
  } else if (editForm.timeType === 'deadline_task') {
    editForm.deadlineTime = replaceDatePart(editForm.deadlineTime, date)
  } else {
    editForm.startTime = replaceDatePart(editForm.startTime, date)
  }
  editScope.value = 'occurrence'
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
  const reminderChanged = editForm.remindAt !== initialRemindAt.value
  let ok: boolean
  if (isRecurring.value && editScope.value === 'series') {
    ok = await store.updateSeries(schedule.value.seriesId || '', { ...editForm, reminderChanged })
  } else {
    ok = await store.editOccurrence(schedule.value.id, { ...editForm, reminderChanged })
  }
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

async function handleDeleteSeries() {
  if (!schedule.value?.seriesId) return
  if (!confirm('确认删除整个重复系列？未完成实例将被删除，已完成实例保留。')) return
  if (await store.deleteSeries(schedule.value.seriesId)) await router.push(returnTarget.value)
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
        <div v-if="isRecurring">
          <span class="muted">重复：</span>
          <span class="tag blue">{{ repeatLabel || '重复' }}</span>
          <span v-if="schedule.occurrenceDate" class="muted" style="margin-left:8px">本次：{{ schedule.occurrenceDate }}</span>
        </div>
        <div v-if="schedule.progressTrackingEnabled">
          <span class="muted">每日进度：</span>
          <span class="tag blue">{{ progressPercentText }}</span>
          <span class="muted" style="margin-left:8px">累计完成负荷 {{ progressLoadTotal() }} 点</span>
        </div>
        <div v-if="schedule.completedAt">
          <span class="muted">完成快照：</span>
          <span v-if="schedule.progressTrackingEnabled">
            于 {{ progressCompletedDate || schedule.completedAt.slice(0, 10) }} 达到 100% 进度完成任务，负荷按每日进度归集
            <template v-if="schedule.completedAt">（操作时间 {{ formatTime(schedule.completedAt) }}）</template>
          </span>
          <span v-else>于 {{ formatTime(schedule.completedAt) }} 完成，实际疲劳 {{ schedule.completedFatigueLevel || '-' }} · {{ schedule.completedFatigueWeight || '-' }} 点</span>
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

      <ScheduleProgressPanel
        v-if="schedule.progressTrackingEnabled"
        class="detail-progress-panel"
        :schedule-id="schedule.id"
        :status="schedule.status"
        :default-fatigue-level="schedule.fatigueLevel || 3"
        @updated="onProgressUpdated"
      />

      <div class="form-actions" style="margin-top:24px">
        <button class="primary" @click="openEdit">编辑</button>
        <button v-if="!schedule.progressTrackingEnabled && schedule.status === 'pending'" @click="handleAction('complete')">标记完成</button>
        <button v-if="schedule.status === 'completed'" @click="handleAction('uncomplete')">恢复</button>
        <button v-if="schedule.status === 'cancelled'" @click="handleAction('restore')">恢复</button>
        <button v-if="schedule.status === 'pending'" @click="handleAction('cancel')">取消日程</button>
        <button @click="handleDelete">删除</button>
        <button v-if="isRecurring" class="danger" @click="handleDeleteSeries">删除整个系列</button>
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
          <div v-if="isRecurring" class="edit-scope">
            <span class="muted">编辑范围</span>
            <label><input type="radio" value="occurrence" v-model="editScope" /> 仅此实例</label>
            <label><input type="radio" value="series" v-model="editScope" /> 整个系列</label>
          </div>
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
          <RepeatRuleEditor v-if="editScope === 'series'" v-model:rrule="editForm.rrule" v-model:excluded-dates="editForm.excludedDates" />
          <template v-if="editProgressToggleVisible">
            <label class="progress-toggle">
              <input v-model="editForm.progressTrackingEnabled" type="checkbox" :disabled="editProgressBlockedByType" />
              每日进度
            </label>
            <small v-if="editProgressBlockedByType" class="muted">「安排事项」不支持每日进度；请先取消勾选并保存，再修改类型。</small>
            <small v-else class="muted">关闭每日进度前需要先把已有进度记录修正为 0。</small>
          </template>
          <FatiguePreviewInline v-if="editFatiguePreview" :preview="editFatiguePreview" />
          <ReminderShortcutPicker v-model="editForm.remindAt" :base-time="reminderBaseTime" :base-label="reminderBaseLabel" :presets="reminderPresets" :timezone="userTimezone" @error="store.notify" />
          <small class="muted">保持原值不会替换提醒；清空后保存会取消未发送提醒。</small>
          <div class="form-actions"><button type="button" @click="editing = false">取消</button><button class="primary" :disabled="store.loading">保存修改</button></div>
        </form>
      </section>
    </div>

  </section>
</template>

<style scoped>
/* 进度面板本体在 components/ScheduleProgressPanel.vue，这里只控制它在详情页中的位置。
   子组件是 fragment 根节点，父组件的 scoped 属性不会落到它身上，必须用 :deep() 才能命中。 */
:deep(.detail-progress-panel) {
  margin-top: 24px;
}
</style>
