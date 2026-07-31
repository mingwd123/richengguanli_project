<script setup>
import { ref, computed } from 'vue'
import { ArrowLeft, ArrowRight, RefreshLeft, Search } from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'

const store = useAdminStore()
const sortableKeys = new Set(['id', 'createdAt', 'status', 'title', 'deadlineTime', 'remindAt', 'phone', 'nickname', 'name', 'username', 'role'])

const props = defineProps({
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  actions: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 20 },
  keyword: { type: String, default: '' },
  status: { type: String, default: '' },
  dateFrom: { type: String, default: '' },
  dateTo: { type: String, default: '' },
})

const emit = defineEmits([
  'search',
  'reset',
  'page-change',
  'size-change',
  'update:keyword',
  'update:status',
  'update:dateFrom',
  'update:dateTo',
])

const localKeyword = ref(props.keyword)
const localStatus = ref(props.status)
const localDateFrom = ref(props.dateFrom)
const localDateTo = ref(props.dateTo)

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))

const gridStyle = computed(() => {
  const colCount = props.columns.length + (props.actions ? 1 : 0)
  return { gridTemplateColumns: `repeat(${colCount}, minmax(120px, 1fr))` }
})

function onSearch() {
  emit('update:keyword', localKeyword.value)
  emit('search', {
    keyword: localKeyword.value,
    status: localStatus.value,
    dateFrom: localDateFrom.value,
    dateTo: localDateTo.value,
  })
}

function resetFilters() {
  localKeyword.value = ''
  localStatus.value = ''
  localDateFrom.value = ''
  localDateTo.value = ''
  emit('update:keyword', '')
  emit('update:status', '')
  emit('update:dateFrom', '')
  emit('update:dateTo', '')
  emit('reset')
}

function formatValue(value) {
  if (value === true) return '是'
  if (value === false) return '否'
  return value ?? '-'
}

function sortBy(column) {
  if (!sortableKeys.has(column.key)) return
  if (store.sortKey === column.key) store.sortOrder = store.sortOrder === 'asc' ? 'desc' : 'asc'
  else { store.sortKey = column.key; store.sortOrder = 'asc' }
  store.fetchList({ page: 1 })
}
</script>

<template>
  <div class="table-container">
    <div class="table-toolbar">
      <div class="search-bar">
        <span class="table-search-field"><el-icon><Search /></el-icon><input v-model="localKeyword" placeholder="搜索关键词" @input="onSearch" /></span>
      </div>
      <div class="filters">
        <select v-model="localStatus" @change="$emit('update:status', localStatus)">
          <option value="">全部状态</option>
          <option value="active">启用</option>
          <option value="disabled">禁用</option>
          <option value="pending">待处理</option>
          <option value="completed">已完成</option>
          <option value="cancelled">已取消</option>
        </select>
        <input type="date" v-model="localDateFrom" @change="$emit('update:dateFrom', localDateFrom)" placeholder="开始日期" />
        <input type="date" v-model="localDateTo" @change="$emit('update:dateTo', localDateTo)" placeholder="结束日期" />
      </div>
      <button class="primary table-command" @click="onSearch"><el-icon><Search /></el-icon><span>查询</span></button>
      <button class="table-command" @click="resetFilters"><el-icon><RefreshLeft /></el-icon><span>重置</span></button>
    </div>

    <div class="data-table">
      <div class="table-row table-title" :style="gridStyle">
        <strong v-for="col in columns" :key="col.key">
          <button v-if="sortableKeys.has(col.key)" class="table-sort" @click="sortBy(col)">
            {{ col.label }}<span v-if="store.sortKey === col.key">{{ store.sortOrder === 'asc' ? '↑' : '↓' }}</span>
          </button>
          <template v-else>{{ col.label }}</template>
        </strong>
        <strong v-if="actions">操作</strong>
      </div>
      <div v-if="loading" class="loading">加载中...</div>
      <div v-for="row in rows" :key="row.id" class="table-row" :style="gridStyle">
        <span v-for="col in columns" :key="col.key">
          <slot :name="'cell-' + col.key" :row="row" :value="formatValue(row[col.key])">
            {{ formatValue(row[col.key]) }}
          </slot>
        </span>
        <span v-if="actions" class="row-actions">
          <slot name="actions" :row="row"></slot>
        </span>
      </div>
      <div v-if="!rows.length && !loading" class="empty">暂无数据</div>
    </div>

    <div class="pagination" v-if="total > 0">
      <span>共 {{ total }} 条</span>
      <button class="pagination-icon" title="上一页" aria-label="上一页" :disabled="page <= 1" @click="$emit('page-change', page - 1)"><el-icon><ArrowLeft /></el-icon></button>
      <span>第 {{ page }} / {{ totalPages }} 页</span>
      <button class="pagination-icon" title="下一页" aria-label="下一页" :disabled="page >= totalPages" @click="$emit('page-change', page + 1)"><el-icon><ArrowRight /></el-icon></button>
      <select :value="size" @change="$emit('size-change', Number($event.target.value))">
        <option :value="10">10条/页</option>
        <option :value="20">20条/页</option>
        <option :value="50">50条/页</option>
      </select>
    </div>
  </div>
</template>
