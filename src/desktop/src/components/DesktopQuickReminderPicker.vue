<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BellRing } from 'lucide-vue-next'
import { matchingReminderOffset, reminderLocalForOffset, reminderPresetLabel } from '../utils/reminder'

const props = defineProps<{
  baseTime: string
  baseLabel: string
  presets: number[]
  timezone: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  error: [message: string]
}>()

const remindAt = defineModel<string>({ required: true })
const normalizedPresets = computed(() => [...new Set(props.presets)]
  .filter(minutes => Number.isInteger(minutes) && minutes > 0)
  .sort((left, right) => left - right))
const selectedOffset = ref<number | null>(matchingReminderOffset(
  props.baseTime,
  remindAt.value,
  normalizedPresets.value,
  props.timezone,
))

function applyOffset(minutes: number) {
  if (!props.baseTime) {
    emit('error', `请先填写${props.baseLabel}`)
    return
  }
  try {
    const value = reminderLocalForOffset(props.baseTime, minutes, props.timezone)
    if (value) {
      selectedOffset.value = minutes
      remindAt.value = value
    }
  } catch (error: any) {
    emit('error', error.message || '无法计算提醒时间')
  }
}

watch(remindAt, value => {
  selectedOffset.value = matchingReminderOffset(
    props.baseTime,
    value,
    normalizedPresets.value,
    props.timezone,
  )
})

watch([() => props.baseTime, () => props.baseLabel], () => {
  if (selectedOffset.value !== null && props.baseTime) applyOffset(selectedOffset.value)
})

watch(normalizedPresets, presets => {
  if (selectedOffset.value !== null && !presets.includes(selectedOffset.value)) selectedOffset.value = null
})
</script>

<template>
  <label class="quick-editor-field">
    <span>提醒时间 <small>可选</small></span>
    <input v-model="remindAt" type="datetime-local" :disabled="disabled" />
  </label>
  <div class="quick-reminder-shortcuts" aria-label="快捷提醒时间">
    <div class="quick-reminder-heading">
      <strong><BellRing :size="14" />快捷提醒</strong>
      <small>{{ baseTime ? `按${baseLabel}计算` : `请先填写${baseLabel}` }}</small>
    </div>
    <div class="quick-reminder-options">
      <button
        v-for="minutes in normalizedPresets"
        :key="minutes"
        type="button"
        :class="{ active: selectedOffset === minutes }"
        :disabled="disabled || !baseTime"
        :aria-pressed="selectedOffset === minutes"
        @click="applyOffset(minutes)"
      >{{ reminderPresetLabel(minutes) }}</button>
      <span v-if="selectedOffset === null && remindAt" class="quick-reminder-custom">自定义</span>
    </div>
  </div>
</template>
