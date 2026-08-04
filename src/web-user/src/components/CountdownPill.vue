<script setup lang="ts">
import { computed } from 'vue'
import type { TimeType } from '../types'
import { countdown, urgency, formatTime } from '../utils/helpers'

const props = defineProps<{
  time: string
  createdAt?: string
  startTime?: string
  endTime?: string
  deadlineTime?: string
  remindAt?: string
  timeType?: TimeType
}>()

const countdownText = computed(() => countdown(props.time, props.timeType))
const urgencyClass = computed(() => urgency(props.time, props.timeType))
const tooltipLines = computed(() => {
  const lines: string[] = []
  if (props.createdAt) lines.push('创建: ' + formatTime(props.createdAt))
  if (props.startTime) lines.push('开始: ' + formatTime(props.startTime))
  if (props.endTime) lines.push('结束: ' + formatTime(props.endTime))
  if (props.deadlineTime) lines.push('截止: ' + formatTime(props.deadlineTime))
  if (props.remindAt) lines.push('提醒: ' + formatTime(props.remindAt))
  if (props.time) {
    const text = countdown(props.time, props.timeType)
    lines.push(text === '已过' ? '状态: 已过' : '剩余: ' + text)
  }
  return lines.join('\n')
})
</script>

<template>
  <span class="tag" :class="urgencyClass" :title="tooltipLines || undefined">{{ countdownText }}</span>
</template>
