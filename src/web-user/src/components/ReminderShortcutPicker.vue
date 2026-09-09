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
const selectedOffset = ref<number | null>(null)

function syncSelectedOffset() {
  selectedOffset.value = matchingReminderOffset(
    props.baseTime,
    remindAt.value,
    normalizedPresets.value,
    props.timezone,
  )
}

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

watch([remindAt, () => props.timezone, normalizedPresets], syncSelectedOffset, { immediate: true })
watch([() => props.baseTime, () => props.baseLabel], () => {
  if (selectedOffset.value !== null && props.baseTime) applyOffset(selectedOffset.value)
  else syncSelectedOffset()
})
</script>

<template>
  <label>提醒时间 <small>可选</small><input v-model="remindAt" type="datetime-local" :disabled="disabled" /></label>
  <div class="reminder-shortcuts" aria-label="快捷提醒时间">
    <div class="reminder-shortcut-head">
      <strong><BellRing :size="14" />快捷提醒</strong>
      <small>{{ baseTime ? `按${baseLabel}计算` : `请先填写${baseLabel}` }}</small>
    </div>
    <div class="reminder-shortcut-options">
      <button
        v-for="minutes in normalizedPresets"
        :key="minutes"
        type="button"
        :class="{ active: selectedOffset === minutes }"
        :disabled="disabled || !baseTime"
        :aria-pressed="selectedOffset === minutes"
        @click="applyOffset(minutes)"
      >{{ reminderPresetLabel(minutes) }}</button>
      <span v-if="selectedOffset === null && remindAt" class="reminder-custom-state">自定义</span>
    </div>
  </div>
</template>
