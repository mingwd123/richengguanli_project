<script setup lang="ts">
import { AlertTriangle, Gauge } from 'lucide-vue-next'
import type { FatiguePreview } from '../types'

defineProps<{ preview: FatiguePreview }>()

function stateLabel(level: string) {
  if (level === 'overloaded') return '可能过载'
  if (level === 'high') return '高负荷'
  if (level === 'tired') return '较疲劳'
  if (level === 'full') return '较满'
  return '舒适'
}
</script>

<template>
  <section :class="['fatigue-inline-preview', { warning: preview.shouldWarn }]">
    <div class="fatigue-inline-head">
      <span><AlertTriangle v-if="preview.shouldWarn" :size="17" /><Gauge v-else :size="17" /></span>
      <div><strong>个人日程预计负荷</strong><small>{{ preview.affectedDates.join('、') }}</small></div>
    </div>
    <div class="fatigue-inline-dates">
      <article v-for="item in preview.dates" :key="item.localDate">
        <span>{{ item.localDate }}</span>
        <div><strong>{{ item.before.predictedScore }}</strong><i>→</i><strong :class="{ elevated: item.after.predictedScore >= 70 }">{{ item.after.predictedScore }} 分</strong></div>
        <small>{{ item.after.plannedLoad }} / {{ item.after.capacity75 }} 点 · {{ stateLabel(item.after.level) }}</small>
        <p v-if="item.warning">{{ item.warning }}</p>
      </article>
    </div>
  </section>
</template>
