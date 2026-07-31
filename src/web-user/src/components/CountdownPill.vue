<script setup lang="ts">
import { computed } from 'vue'
import { countdown, urgency, formatTime } from '../utils/helpers'

const props = defineProps<{
  time: string
  createdAt?: string
  startTime?: string
  endTime?: string
  deadlineTime?: string
  remindAt?: string
}>()

const countdownText = computed(() => countdown(props.time))
const urgencyClass = computed(() => urgency(props.time))
const tooltipLines = computed(() => {
  const lines: string[] = []
  if (props.createdAt) lines.push('创建: ' + formatTime(props.createdAt))
  if (props.startTime) lines.push('开始: ' + formatTime(props.startTime))
  if (props.endTime) lines.push('结束: ' + formatTime(props.endTime))
  if (props.deadlineTime) lines.push('截止: ' + formatTime(props.deadlineTime))
  if (props.remindAt) lines.push('提醒: ' + formatTime(props.remindAt))
  if (props.time) lines.push('剩余: ' + countdown(props.time))
  return lines.join('\n')
})
</script>

<template>
  <span class="tag" :class="urgencyClass" :title="tooltipLines || undefined">{{ countdownText }}</span>
</template>
