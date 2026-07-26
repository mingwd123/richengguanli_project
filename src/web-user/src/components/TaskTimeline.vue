<script setup lang="ts">
import { computed } from 'vue'
import type { TimelineItem } from '../types'
import { formatTime, urgency } from '../utils/helpers'

const props = defineProps<{
  items: TimelineItem[]
}>()

const sliced = computed(() => props.items.slice(0, 8))
</script>

<template>
  <div class="timeline-track">
    <article
      v-for="item in sliced"
      :key="`${item.sourceType}-${item.id}-${item.assignRound || 0}`"
      :class="['timeline-entry', item.kind]"
    >
      <div class="time-node">
        <span :class="['line-dot', urgency(item.sortAt)]"></span>
      </div>
      <div class="timeline-card">
        <div class="timeline-card-top">
          <span class="time-text">{{ formatTime(item.sortAt) }}</span>
          <span class="kind-chip">{{ item.kindLabel }}</span>
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
