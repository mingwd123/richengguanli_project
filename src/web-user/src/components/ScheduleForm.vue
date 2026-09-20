<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useAppStore } from '../stores/app'
import { labels } from '../utils/labels'
import { getDisplayTimezone } from '../utils/helpers'
import ScheduleLevelControl from './ScheduleLevelControl.vue'
import ReminderShortcutPicker from './ReminderShortcutPicker.vue'

const store = useAppStore()
const urgencyNames = ['不紧急', '较低', '普通', '紧急', '非常紧急']
const fatigueNames = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']
const reminderPresets = computed(() => store.notificationPreferences.reminderPresetMinutes || [])
const userTimezone = computed(() => store.profile?.timezone || getDisplayTimezone())
const reminderBaseTime = computed(() => store.scheduleForm.timeType === 'deadline_task' ? store.scheduleForm.deadlineTime : store.scheduleForm.startTime)
const reminderBaseLabel = computed(() => store.scheduleForm.timeType === 'deadline_task' ? '截止时间' : '开始时间')

const weekdays = [
  { code: 'MO', label: '一' }, { code: 'TU', label: '二' }, { code: 'WE', label: '三' },
  { code: 'TH', label: '四' }, { code: 'FR', label: '五' }, { code: 'SA', label: '六' }, { code: 'SU', label: '日' },
]

const repeat = reactive({
  type: 'none' as 'none' | 'daily' | 'weekly' | 'monthly' | 'custom',
  interval: 1,
  weekdays: [] as string[],
  customRrule: '',
})
const excludeDateInput = ref('')

function builtRrule() {
  if (repeat.type === 'none') return ''
  if (repeat.type === 'custom') return repeat.customRrule.trim()
  const intervalPart = repeat.interval > 1 ? `;INTERVAL=${repeat.interval}` : ''
  if (repeat.type === 'daily') return `FREQ=DAILY${intervalPart}`
  if (repeat.type === 'monthly') return `FREQ=MONTHLY${intervalPart}`
  const byday = repeat.weekdays.length ? `;BYDAY=${repeat.weekdays.join(',')}` : ''
  return `FREQ=WEEKLY${intervalPart}${byday}`
}

watch(() => builtRrule(), () => { store.scheduleForm.rrule = builtRrule() })

function toggleWeekday(code: string) {
  const index = repeat.weekdays.indexOf(code)
  if (index >= 0) repeat.weekdays.splice(index, 1)
  else repeat.weekdays.push(code)
}

function addExcludedDate() {
  const value = excludeDateInput.value
  if (!value) return
  const list = store.scheduleForm.excludedDates || []
  if (!list.includes(value)) store.scheduleForm.excludedDates = [...list, value].sort()
  excludeDateInput.value = ''
}

function removeExcludedDate(value: string) {
  store.scheduleForm.excludedDates = (store.scheduleForm.excludedDates || []).filter(d => d !== value)
}

// 每日进度只适用于任务类型且与重复规则互斥；单日/点事件沿用一次性完成流程。
const canTrackProgress = computed(() => {
  const type = store.scheduleForm.timeType
  return (type === 'deadline_task' || type === 'duration_task') && !store.scheduleForm.rrule
})

watch(canTrackProgress, (value) => {
  if (!value) store.scheduleForm.progressTrackingEnabled = false
})
</script>

<template>
  <form @submit.prevent="store.createSchedule()">
    <label>
      {{ labels.title }}
      <input v-model="store.scheduleForm.title" />
    </label>
    <label>描述<textarea v-model="store.scheduleForm.description" rows="3"></textarea></label>
    <label>
      {{ labels.module }}
      <select v-model="store.scheduleForm.groupId">
        <option v-for="group in store.taskGroups" :key="group.id" :value="group.id">
          {{ group.name }}
        </option>
      </select>
    </label>
    <label>
      {{ labels.type }}
      <select v-model="store.scheduleForm.timeType">
        <option value="point_event">{{ labels.pointEvent }}</option>
        <option value="deadline_task">{{ labels.deadlineTask }}</option>
        <option value="duration_task">{{ labels.durationTask }}</option>
      </select>
    </label>
    <div class="level-form-grid">
      <ScheduleLevelControl v-model="store.scheduleForm.urgencyLevel" label="紧急度" :labels="urgencyNames" />
      <ScheduleLevelControl v-model="store.scheduleForm.fatigueLevel" label="预计疲劳度" :labels="fatigueNames" :weights="store.fatigueProfile?.weights" />
    </div>
    <label v-if="store.scheduleForm.timeType === 'point_event'">
      {{ labels.occurTime }}
      <input v-model="store.scheduleForm.startTime" type="datetime-local" />
    </label>
    <label v-if="store.scheduleForm.timeType === 'deadline_task'">
      {{ labels.deadlineTime }}
      <input v-model="store.scheduleForm.deadlineTime" type="datetime-local" />
    </label>
    <label v-if="store.scheduleForm.timeType === 'duration_task'">
      {{ labels.startTime }}
      <input v-model="store.scheduleForm.startTime" type="datetime-local" />
    </label>
    <ReminderShortcutPicker v-model="store.scheduleForm.remindAt" :base-time="reminderBaseTime" :base-label="reminderBaseLabel" :presets="reminderPresets" :timezone="userTimezone" @error="store.notify" />
    <label v-if="store.scheduleForm.timeType === 'duration_task'">
      {{ labels.endTime }}
      <input v-model="store.scheduleForm.endTime" type="datetime-local" />
    </label>

    <fieldset class="repeat-block">
      <legend>重复</legend>
      <label>
        重复方式
        <select v-model="repeat.type">
          <option value="none">不重复</option>
          <option value="daily">每天</option>
          <option value="weekly">每周</option>
          <option value="monthly">每月</option>
          <option value="custom">自定义</option>
        </select>
      </label>

      <label v-if="repeat.type !== 'none' && repeat.type !== 'custom'" class="inline-field">
        间隔
        <input v-model.number="repeat.interval" type="number" min="1" max="999" />
      </label>

      <div v-if="repeat.type === 'weekly'" class="weekday-group">
        <span class="muted">重复星期</span>
        <button v-for="day in weekdays" :key="day.code" type="button"
                :class="['chip', repeat.weekdays.includes(day.code) ? 'chip-on' : '']"
                @click="toggleWeekday(day.code)">周{{ day.label }}</button>
      </div>

      <label v-if="repeat.type === 'custom'">
        RRULE 规则
        <input v-model="repeat.customRrule" placeholder="FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE" />
      </label>

      <div v-if="repeat.type !== 'none'" class="exdate-block">
        <span class="muted">排除日期（EXDATE）</span>
        <div class="exdate-row">
          <input v-model="excludeDateInput" type="date" />
          <button type="button" @click="addExcludedDate">添加</button>
        </div>
        <div v-if="store.scheduleForm.excludedDates?.length" class="exdate-list">
          <button v-for="date in store.scheduleForm.excludedDates" :key="date" type="button" class="chip chip-off" @click="removeExcludedDate(date)">
            {{ date }} ✕
          </button>
        </div>
      </div>
    </fieldset>

    <fieldset v-if="canTrackProgress" class="progress-block">
      <legend>每日进度</legend>
      <label class="progress-toggle">
        <input v-model="store.scheduleForm.progressTrackingEnabled" type="checkbox" />
        启用每日进度
      </label>
      <small class="muted">适用于跨天或长期任务：按天提交累计完成比例，并选择当天推进对应的疲劳程度。</small>
    </fieldset>

    <div class="form-actions">
      <button type="button" @click="store.closeScheduleModal()">{{ labels.cancel }}</button>
      <button class="primary">{{ labels.save }}</button>
    </div>
  </form>
</template>

<style scoped>
.repeat-block {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
  padding: 12px;
  margin: 8px 0;
  display: grid;
  gap: 10px;
}
.repeat-block legend {
  font-weight: 600;
  padding: 0 6px;
}
.inline-field {
  display: flex;
  align-items: center;
  gap: 8px;
}
.inline-field input {
  width: 80px;
}
.weekday-group {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  border: 1px solid var(--border, #e5e7eb);
  background: transparent;
  border-radius: 999px;
  padding: 2px 10px;
  cursor: pointer;
  font-size: 13px;
}
.chip-on {
  background: #2f80ed;
  color: #fff;
  border-color: #2f80ed;
}
.chip-off {
  color: #b42318;
}
.exdate-block {
  display: grid;
  gap: 6px;
}
.exdate-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.exdate-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.progress-block {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
  padding: 12px;
  margin: 8px 0;
  display: grid;
  gap: 6px;
}
.progress-block legend {
  font-weight: 600;
  padding: 0 6px;
}
.progress-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>