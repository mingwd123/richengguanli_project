<script setup lang="ts">
import { computed } from 'vue'
import { statusLabel } from '../utils/helpers'

const props = defineProps<{
  status: string
}>()

function statusColor(s: string): string {
  const map: Record<string, string> = {
    pending: '',
    completed: 'green',
    cancelled: '',
    active: 'blue',
    accepted: 'blue',
    rejected: 'warning',
    all_rejected: 'danger',
  }
  return map[s] || ''
}

const label = computed(() => statusLabel(props.status))
const colorClass = computed(() => statusColor(props.status))
</script>

<template>
  <span class="tag" :class="colorClass">{{ label }}</span>
</template>

<style scoped>
.tag.green {
  background: #ecfdf5;
  color: #047857;
}
</style>
