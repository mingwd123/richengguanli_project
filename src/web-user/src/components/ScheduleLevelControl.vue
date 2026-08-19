<script setup lang="ts">
const props = defineProps<{
  modelValue: number
  label: string
  labels: string[]
  weights?: Record<string, number>
}>()

const emit = defineEmits<{ 'update:modelValue': [value: number] }>()

function select(level: number) {
  emit('update:modelValue', level)
}
</script>

<template>
  <fieldset class="schedule-level-control">
    <legend>{{ label }}</legend>
    <div class="schedule-level-segments">
      <button
        v-for="level in 5"
        :key="level"
        type="button"
        :class="{ active: Number(props.modelValue) === level }"
        :aria-pressed="Number(props.modelValue) === level"
        :title="`${level} · ${labels[level - 1]}`"
        @click="select(level)"
      >
        <strong>{{ level }}</strong>
        <span>{{ labels[level - 1] }}</span>
      </button>
    </div>
    <small v-if="weights">当前选择系数 {{ weights[String(modelValue)] ?? [1, 2, 3, 5, 8][Number(modelValue || 3) - 1] }} 点</small>
  </fieldset>
</template>
