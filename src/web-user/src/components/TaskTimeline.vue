<script setup lang="ts">
import { computed } from 'vue'
import type { TimelineItem } from '../types'
import { countdown, formatTime, urgency } from '../utils/helpers'

const props = defineProps<{
  items: TimelineItem[]
}>()

const sliced = computed(() => props.items.slice(0, 8).map(item => {
  const endTime = item.endTime || item.deadlineTime || item.sortAt
  const isDuration = item.kind === 'duration_task' && !!item.startTime && !!endTime
  return {
    ...item,
    endTime,
    isDuration,
    displayStart: formatTime(isDuration ? (item.startTime || item.sortAt) : item.sortAt),
    displayEnd: isDuration ? formatTime(endTime) : ''
  }
}))
</script>

<template>
  <div class="timeline-track task-timeline">
    <article
      v-for="item in sliced"
      :key="`${item.sourceType}-${item.id}-${item.assignRound || 0}`"
      :class="['timeline-entry', 'task-timeline-entry', item.kind, { duration: item.isDuration }]"
    >
      <div class="timeline-left task-timeline-left">
        <template v-if="item.isDuration">
          <span class="timeline-date task-timeline-date">{{ item.displayStart.split(' ')[0] }}</span>
          <span class="timeline-time task-timeline-time">{{ item.displayStart.split(' ')[1] || '' }}</span>
          <span class="timeline-range-separator">-</span>
          <span class="timeline-date task-timeline-date">{{ item.displayEnd.split(' ')[0] }}</span>
          <span class="timeline-time task-timeline-time">{{ item.displayEnd.split(' ')[1] || '' }}</span>
        </template>
        <template v-else>
          <span class="timeline-date task-timeline-date">{{ item.displayStart.split(' ')[0] }}</span>
          <span class="timeline-time task-timeline-time">{{ item.displayStart.split(' ')[1] || '' }}</span>
        </template>
      </div>
      <div class="timeline-dot-wrap task-timeline-dot-wrap">
        <span v-if="item.isDuration" class="timeline-duration-marker">
          <span class="timeline-duration-node start"></span>
          <span class="timeline-duration-rail"></span>
          <span class="timeline-duration-node end"></span>
        </span>
        <span v-else :class="['line-dot', urgency(item.endTime, item.kind)]"></span>
      </div>
      <div class="timeline-card">
        <div class="timeline-card-top">
          <span class="kind-chip">{{ item.kindLabel }}</span>
          <span class="timeline-summary-text">{{ countdown(item.endTime, item.kind) }}</span>
        </div>
        <strong>{{ item.title }}</strong>
        <small>{{ item.sourceLabel }}</small>
      </div>
    </article>
    <div v-if="items.length === 0" class="timeline-empty">
      <strong>暂无数据</strong>
    </div>
  </div>
</template>
