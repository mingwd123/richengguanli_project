<script setup>
import { onMounted, ref, computed } from 'vue'
import { useAdminStore } from '../stores/admin'

const store = useAdminStore()

const adminIdFilter = ref('')
const actionFilter = ref('')
const targetTypeFilter = ref('')
const keywordFilter = ref('')
const dateFromFilter = ref('')
const dateToFilter = ref('')
const showDetail = ref(false)
const logDetail = ref(null)

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'adminId', label: '管理员ID' },
  { key: 'action', label: '操作' },
  { key: 'targetType', label: '目标类型' },
  { key: 'targetId', label: '目标ID' },
  { key: 'beforeData', label: '修改前' },
  { key: 'afterData', label: '修改后' },
  { key: 'ipAddress', label: 'IP地址' },
  { key: 'createdAt', label: '时间' },
]

onMounted(async () => {
  await fetchLogs()
})

async function fetchLogs(pageNum = 1) {
  await store.fetchOperationLogs(
    pageNum,
    store.page.size,
    adminIdFilter.value,
    actionFilter.value,
    targetTypeFilter.value,
    dateFromFilter.value,
    dateToFilter.value,
    keywordFilter.value,
  )
}

function onSearch() {
  fetchLogs(1)
}

function onReset() {
  adminIdFilter.value = ''
  actionFilter.value = ''
  targetTypeFilter.value = ''
  keywordFilter.value = ''
  dateFromFilter.value = ''
  dateToFilter.value = ''
  fetchLogs(1)
}

function onPageChange(page) {
  fetchLogs(page)
}

function onSizeChange(size) {
  store.page.size = size
  fetchLogs(1)
}

function viewDetail(row) {
  logDetail.value = row
  showDetail.value = true
}

function closeDetail() {
  showDetail.value = false
  logDetail.value = null
}

function formatJson(value) {
  if (!value) return '-'
  try {
    const obj = typeof value === 'string' ? JSON.parse(value) : value
    return JSON.stringify(obj, null, 2)
  } catch { return String(value) }
}
</script>

<template>
  <div class="resource-page">
    <header class="topbar">
      <div>
        <h1>操作日志</h1>
        <p>查看管理员操作历史。</p>
      </div>
      <button class="primary" @click="fetchLogs()">刷新</button>
    </header>

    <div class="table-container">
      <div class="table-toolbar">
        <div class="search-bar">
          <input v-model="adminIdFilter" placeholder="管理员ID" />
        </div>
        <div class="filters">
          <input v-model="actionFilter" placeholder="操作关键字" />
          <input v-model="targetTypeFilter" placeholder="目标类型" />
          <input v-model="keywordFilter" placeholder="综合搜索" />
          <input type="date" v-model="dateFromFilter" placeholder="开始日期" />
          <input type="date" v-model="dateToFilter" placeholder="结束日期" />
        </div>
        <button class="primary" @click="onSearch">查询</button>
        <button @click="onReset">重置</button>
      </div>

      <div class="data-table">
        <div class="table-row table-title" style="grid-template-columns: repeat(9, minmax(100px, 1fr));">
          <strong v-for="col in columns" :key="col.key">{{ col.label }}</strong>
        </div>
        <div v-if="store.loading" class="loading">加载中...</div>
        <div v-for="row in store.page.list" :key="row.id" class="table-row" style="grid-template-columns: repeat(9, minmax(100px, 1fr)); cursor:pointer" @click="viewDetail(row)">
          <span v-for="col in columns" :key="col.key" :title="String(store.formatValue(row[col.key]))">
            <template v-if="col.key === 'beforeData' || col.key === 'afterData'">
              <span v-if="row[col.key]" class="detail-link" @click.stop="viewDetail(row)">查看</span>
              <span v-else>-</span>
            </template>
            <template v-else>
              {{ store.formatValue(row[col.key]) }}
            </template>
          </span>
        </div>
        <div v-if="!store.page.list.length && !store.loading" class="empty">暂无数据</div>
      </div>

      <div class="pagination" v-if="store.page.total > 0">
        <span>共 {{ store.page.total }} 条</span>
        <button :disabled="store.page.page <= 1" @click="onPageChange(store.page.page - 1)">上一页</button>
        <span>第 {{ store.page.page }} / {{ Math.max(1, Math.ceil(store.page.total / store.page.size)) }} 页</span>
        <button :disabled="store.page.page >= Math.ceil(store.page.total / store.page.size)" @click="onPageChange(store.page.page + 1)">下一页</button>
        <select :value="store.page.size" @change="onSizeChange(Number($event.target.value))">
          <option :value="10">10条/页</option>
          <option :value="20">20条/页</option>
          <option :value="50">50条/页</option>
        </select>
      </div>
    </div>

    <!-- Detail Modal -->
    <div v-if="showDetail && logDetail" class="modal-overlay" @click.self="closeDetail">
      <div class="detail-modal" style="max-width: 600px;">
        <div class="modal-header">
          <h2>操作日志详情</h2>
          <button @click="closeDetail">&times;</button>
        </div>
        <div class="modal-body">
          <div class="detail-grid">
            <div class="detail-item" v-for="(value, key) in logDetail" :key="key">
              <span class="detail-label">{{ key }}</span>
              <span class="detail-value" v-if="key === 'beforeData' || key === 'afterData'" style="white-space:pre-wrap;font-family:monospace;font-size:11px;max-height:160px;overflow:auto">{{ formatJson(value) }}</span>
              <span class="detail-value" v-else>{{ store.formatValue(value) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-link { color: #2f80ed; text-decoration: underline; cursor: pointer; }
</style>
