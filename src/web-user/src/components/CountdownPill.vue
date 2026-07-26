<script setup lang="ts">
import { computed } from 'vue'
import { countdown, urgency } from '../utils/helpers'

const props = defineProps<{
  time: string
}>()

const countdownText = computed(() => countdown(props.time))
const urgencyClass = computed(() => urgency(props.time))
</script>

<template>
  <span class="tag" :class="urgencyClass">{{ countdownText }}</span>
</template>
