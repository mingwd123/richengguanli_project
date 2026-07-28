<script setup lang="ts">
defineProps<{
  x: number
  y: number
  items: { label: string; action: string; disabled?: boolean }[]
}>()

const emit = defineEmits<{
  select: [action: string]
  close: []
}>()
</script>

<template>
  <div class="context-backdrop" @click="emit('close')" @contextmenu.prevent="emit('close')">
    <div class="context-menu" :style="{ left: x + 'px', top: y + 'px' }" @click.stop>
      <button
        v-for="item in items"
        :key="item.action"
        :disabled="item.disabled"
        @click="emit('select', item.action)"
      >
        {{ item.label }}
      </button>
    </div>
  </div>
</template>
