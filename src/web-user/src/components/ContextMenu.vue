<script setup lang="ts">
defineProps<{
  x: number
  y: number
  items: ({ label?: string; action?: string; disabled?: boolean; separator?: boolean })[]
}>()

const emit = defineEmits<{
  select: [action: string]
  close: []
}>()
</script>

<template>
  <div class="context-backdrop" @click="emit('close')" @contextmenu.prevent="emit('close')">
    <div class="context-menu" :style="{ left: x + 'px', top: y + 'px' }" @click.stop>
      <template v-for="(item, i) in items" :key="i">
        <hr v-if="item.separator" class="context-sep" />
        <button
          v-else
          :disabled="item.disabled"
          @click="item.action ? emit('select', item.action) : undefined"
        >
          {{ item.label }}
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.context-sep { border: none; border-top: 1px solid #e2e8f0; margin: 4px 0; }
</style>
