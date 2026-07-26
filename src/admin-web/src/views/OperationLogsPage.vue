<script setup>
import { onMounted, ref } from 'vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'

const store = useAdminStore()

const adminIdFilter = ref('')
const actionFilter = ref('')
const targetTypeFilter = ref('')
const dateFromFilter = ref('')
const dateToFilter = ref('')

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'adminId', label: '管理员ID' },
  { key: 'action', label: '操作' },
  { key: 'targetType', label: '目标类型' },
  { key: 'targetId', label: '目标ID' },
  { key: 'ipAddress', label: 'IP地址' },
  { key: 'userAgent', label: 'User-Agent' },
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
  )
}

function onSearch() {
  fetchLogs(1)
}

function onReset() {
  adminIdFilter.value = ''
  actionFilter.value = ''
  targetTypeFilter.value = ''
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
          <input type="date" v-model="dateFromFilter" placeholder="开始日期" />
          <input type="date" v-model="dateToFilter" placeholder="结束日期" />
        </div>
        <button class="primary" @click="onSearch">查询</button>
        <button @click="onReset">重置</button>
      </div>

      <div class="data-table">
        <div class="table-row table-title" style="grid-template-columns: repeat(8, minmax(120px, 1fr));">
          <strong v-for="col in columns" :key="col.key">{{ col.label }}</strong>
        </div>
        <div v-if="store.loading" class="loading">加载中...</div>
        <div v-for="row in store.page.list" :key="row.id" class="table-row" style="grid-template-columns: repeat(8, minmax(120px, 1fr));">
          <span v-for="col in columns" :key="col.key" :title="String(store.formatValue(row[col.key]))">
            {{ store.formatValue(row[col.key]) }}
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
  </div>
</template>
