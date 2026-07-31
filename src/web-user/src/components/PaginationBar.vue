<script setup lang="ts">
import { computed } from 'vue'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'

const props = defineProps<{ page: number; size: number; total: number; loading?: boolean }>()
const emit = defineEmits<{ change: [page: number]; resize: [size: number] }>()
const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
</script>

<template>
  <nav v-if="total > 0" class="pagination-bar" aria-label="列表分页">
    <span>共 {{ total }} 条</span>
    <label class="page-size-control">
      <span>每页</span>
      <select :value="size" aria-label="每页条数" :disabled="loading" @change="emit('resize', Number(($event.target as HTMLSelectElement).value))">
        <option :value="10">10</option>
        <option :value="12">12</option>
        <option :value="20">20</option>
      </select>
    </label>
    <button class="icon-button" title="上一页" aria-label="上一页" :disabled="loading || page <= 1" @click="emit('change', page - 1)">
      <ChevronLeft :size="16" />
    </button>
    <span>第 {{ page }} / {{ totalPages }} 页</span>
    <button class="icon-button" title="下一页" aria-label="下一页" :disabled="loading || page >= totalPages" @click="emit('change', page + 1)">
      <ChevronRight :size="16" />
    </button>
  </nav>
</template>
