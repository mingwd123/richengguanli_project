<script setup lang="ts">
import { computed } from 'vue'
import { BatteryMedium, CalendarClock, Flag, Save, Sparkles, Timer, TriangleAlert } from 'lucide-vue-next'
import type { ScheduleForm, TaskGroup, TimeType } from '@web/types'
import RepeatRuleEditor from '@web/components/RepeatRuleEditor.vue'
import DesktopQuickReminderPicker from './DesktopQuickReminderPicker.vue'

const props = defineProps<{
  groups: TaskGroup[]
  busy: boolean
  submitLabel: string
  reminderPresets: number[]
  timezone: string
  aiEnabled?: boolean
  aiBusy?: boolean
  aiError?: string
  aiNotice?: string
}>()

const emit = defineEmits<{
  submit: []
  error: [message: string]
  aiParse: []
}>()

const form = defineModel<ScheduleForm>({ required: true })

const modes: Array<{ value: TimeType; label: string }> = [
  { value: 'point_event', label: '瞬时' },
  { value: 'deadline_task', label: '截止' },
  { value: 'duration_task', label: '持续' },
]

const levelLabels = {
  urgency: ['不紧急', '较低', '普通', '紧急', '非常紧急'],
  fatigue: ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累'],
}

const reminderBaseTime = computed(() => form.value.timeType === 'deadline_task'
  ? form.value.deadlineTime
  : form.value.startTime)
const reminderBaseLabel = computed(() => form.value.timeType === 'deadline_task' ? '截止时间' : '开始时间')
</script>

<template>
  <form class="quick-editor" @submit.prevent="emit('submit')">
    <label class="quick-editor-field quick-editor-title">
      <span>标题</span>
      <input v-model="form.title" maxlength="200" autofocus placeholder="要处理什么？" />
    </label>
    <div v-if="props.aiEnabled" class="quick-ai-parse-row">
      <button type="button" class="quick-ai-parse-button" :disabled="props.aiBusy || busy" @click="emit('aiParse')"><Sparkles :size="15" />{{ props.aiBusy ? '解析中...' : 'AI 解析' }}</button>
      <span v-if="props.aiError" class="error">{{ props.aiError }}</span>
      <span v-else-if="props.aiNotice" class="success">{{ props.aiNotice }}</span>
    </div>

    <fieldset class="quick-editor-field">
      <legend>时间模式</legend>
      <div class="quick-type-segments">
        <button v-for="mode in modes" :key="mode.value" type="button" :class="{ active: form.timeType === mode.value }" @click="form.timeType = mode.value">
          <CalendarClock v-if="mode.value === 'point_event'" :size="15" />
          <Flag v-else-if="mode.value === 'deadline_task'" :size="15" />
          <Timer v-else :size="15" />
          {{ mode.label }}
        </button>
      </div>
    </fieldset>

    <label class="quick-editor-field">
      <span>分组</span>
      <select v-model="form.groupId">
        <option value="" disabled>选择分组</option>
        <option v-for="group in groups" :key="group.id" :value="String(group.id)">{{ group.name }}</option>
      </select>
    </label>

    <div class="quick-level-grid">
      <fieldset class="quick-editor-field quick-level-field">
        <legend><TriangleAlert :size="14" />紧急度</legend>
        <div class="quick-level-options">
          <button v-for="level in 5" :key="`quick-urgency-${level}`" type="button" :class="{ active: form.urgencyLevel === level }" :aria-label="`紧急度 ${level} ${levelLabels.urgency[level - 1]}`" @click="form.urgencyLevel = level"><strong>{{ level }}</strong><small>{{ levelLabels.urgency[level - 1] }}</small></button>
        </div>
      </fieldset>
      <fieldset class="quick-editor-field quick-level-field">
        <legend><BatteryMedium :size="14" />预计疲劳</legend>
        <div class="quick-level-options fatigue">
          <button v-for="level in 5" :key="`quick-fatigue-${level}`" type="button" :class="{ active: form.fatigueLevel === level }" :aria-label="`预计疲劳度 ${level} ${levelLabels.fatigue[level - 1]}`" @click="form.fatigueLevel = level"><strong>{{ level }}</strong><small>{{ levelLabels.fatigue[level - 1] }}</small></button>
        </div>
      </fieldset>
    </div>

    <label v-if="form.timeType === 'point_event'" class="quick-editor-field">
      <span>发生时间</span>
      <input v-model="form.startTime" type="datetime-local" />
    </label>

    <label v-if="form.timeType === 'deadline_task'" class="quick-editor-field">
      <span>截止时间</span>
      <input v-model="form.deadlineTime" type="datetime-local" />
    </label>

    <div v-if="form.timeType === 'duration_task'" class="quick-editor-time-grid">
      <label class="quick-editor-field"><span>开始时间</span><input v-model="form.startTime" type="datetime-local" /></label>
      <label class="quick-editor-field"><span>结束时间</span><input v-model="form.endTime" type="datetime-local" /></label>
    </div>

    <DesktopQuickReminderPicker v-model="form.remindAt" :base-time="reminderBaseTime" :base-label="reminderBaseLabel" :presets="props.reminderPresets" :timezone="props.timezone" :disabled="busy" @error="emit('error', $event)" />

    <RepeatRuleEditor v-model:rrule="form.rrule" v-model:excluded-dates="form.excludedDates" />

    <label class="quick-editor-field">
      <span>说明 <small>可选</small></span>
      <textarea v-model="form.description" rows="4" placeholder="补充上下文或注意事项"></textarea>
    </label>

    <div v-if="!groups.length" class="quick-editor-warning">暂无个人分组，请在完整工作台中先创建分组。</div>

    <footer class="quick-editor-actions">
      <button class="primary" :disabled="busy || props.aiBusy || !groups.length"><Save :size="16" />{{ busy ? '保存中...' : submitLabel }}</button>
    </footer>
  </form>
</template>
