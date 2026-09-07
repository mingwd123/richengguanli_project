<script setup lang="ts">
import { reactive, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  rrule?: string
  excludedDates?: string[]
}>(), { rrule: '', excludedDates: () => [] })

const emit = defineEmits<{
  (e: 'update:rrule', value: string): void
  (e: 'update:excludedDates', value: string[]): void
}>()

type RepeatType = 'none' | 'daily' | 'weekly' | 'monthly' | 'custom'

const weekdays = [
  { code: 'MO', label: '一' }, { code: 'TU', label: '二' }, { code: 'WE', label: '三' },
  { code: 'TH', label: '四' }, { code: 'FR', label: '五' }, { code: 'SA', label: '六' }, { code: 'SU', label: '日' },
]

function parseInitial(rrule?: string): { type: RepeatType; interval: number; weekdays: string[]; customRrule: string } {
  if (!rrule) return { type: 'none', interval: 1, weekdays: [], customRrule: '' }
  const parts = String(rrule).split(';').map(p => p.trim())
  const freq = parts.find(p => p.toUpperCase().startsWith('FREQ='))?.split('=')[1]?.toUpperCase()
  const interval = Number(parts.find(p => p.toUpperCase().startsWith('INTERVAL='))?.split('=')[1] || 1) || 1
  const byday = parts.find(p => p.toUpperCase().startsWith('BYDAY='))?.split('=')[1]?.split(',').map(s => s.trim()).filter(Boolean) || []
  const hasOrdinal = byday.some(d => /^-?\d/.test(d))
  if (freq === 'DAILY' && byday.length === 0) return { type: 'daily', interval, weekdays: [], customRrule: '' }
  if (freq === 'WEEKLY') return { type: 'weekly', interval, weekdays: hasOrdinal ? [] : byday, customRrule: hasOrdinal ? String(rrule) : '' }
  if (freq === 'MONTHLY' && byday.length === 0) return { type: 'monthly', interval, weekdays: [], customRrule: '' }
  return { type: 'custom', interval, weekdays: [], customRrule: String(rrule) }
}

const initial = parseInitial(props.rrule)
const repeat = reactive<{ type: RepeatType; interval: number; weekdays: string[]; customRrule: string }>(initial)
const excludeDateInput = ref('')

function builtRrule(): string {
  if (repeat.type === 'none') return ''
  if (repeat.type === 'custom') return repeat.customRrule.trim()
  const intervalPart = repeat.interval > 1 ? `;INTERVAL=${repeat.interval}` : ''
  if (repeat.type === 'daily') return `FREQ=DAILY${intervalPart}`
  if (repeat.type === 'monthly') return `FREQ=MONTHLY${intervalPart}`
  const byday = repeat.weekdays.length ? `;BYDAY=${repeat.weekdays.join(',')}` : ''
  return `FREQ=WEEKLY${intervalPart}${byday}`
}

watch(() => builtRrule(), value => emit('update:rrule', value))

function toggleWeekday(code: string) {
  const index = repeat.weekdays.indexOf(code)
  if (index >= 0) repeat.weekdays.splice(index, 1)
  else repeat.weekdays.push(code)
}

function addExcludedDate() {
  const value = excludeDateInput.value
  if (!value) return
  const list = props.excludedDates || []
  if (!list.includes(value)) emit('update:excludedDates', [...list, value].sort())
  excludeDateInput.value = ''
}

function removeExcludedDate(value: string) {
  emit('update:excludedDates', (props.excludedDates || []).filter(d => d !== value))
}
</script>

<template>
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
      <div v-if="(excludedDates || []).length" class="exdate-list">
        <button v-for="date in excludedDates" :key="date" type="button" class="chip chip-off" @click="removeExcludedDate(date)">
          {{ date }} ✕
        </button>
      </div>
    </div>
  </fieldset>
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
</style>