<script setup>
import { onMounted, ref } from 'vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'

const store = useAdminStore()
const showDetail = ref(false)
const statusOptions = [
  { value: 'pending_approval', label: '待团队审批' },
  { value: 'active', label: '进行中' },
  { value: 'unassigned', label: '待重新分配' },
  { value: 'completed', label: '已完成' },
  { value: 'all_rejected', label: '全部拒绝' },
  { value: 'approval_rejected', label: '审批未通过' },
  { value: 'cancelled', label: '已取消' },
]

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'teamId', label: '团队ID' },
  { key: 'creatorId', label: '创建者ID' },
  { key: 'title', label: '标题' },
  { key: 'status', label: '状态' },
  { key: 'deadlineTime', label: '截止时间' },
  { key: 'createdAt', label: '创建时间' },
]

onMounted(async () => {
  store.activeResource = 'teamTasks'
  store.resetFilters()
  await store.fetchList()
})

function onSearch(params) {
  store.searchKeyword = params.keyword
  store.filterStatus = params.status
  store.filterDateFrom = params.dateFrom
  store.filterDateTo = params.dateTo
  store.fetchList({ page: 1 })
}

function onReset() {
  store.resetFilters()
  store.fetchList({ page: 1 })
}

function onPageChange(page) {
  store.fetchList({ page })
}

function onSizeChange(size) {
  store.fetchList({ page: 1, size })
}

async function viewDetail(row) {
  await store.fetchTeamTaskDetail(row.id)
  showDetail.value = true
}

function closeDetail() {
  showDetail.value = false
  store.currentDetail = null
}
</script>

<template>
  <div class="resource-page">
    <header class="topbar">
      <div>
        <h1>团队任务</h1>
        <p>查看所有团队任务。</p>
      </div>
      <button class="primary" @click="store.fetchList()">刷新</button>
    </header>

    <section class="stats">
      <article><span>总数</span><strong>{{ store.stats.total }}</strong></article>
      <article><span>启用</span><strong>{{ store.stats.active }}</strong></article>
      <article><span>待处理</span><strong>{{ store.stats.pending }}</strong></article>
    </section>

    <DataTable
      :columns="columns"
      :rows="store.page.list"
      :loading="store.loading"
      :actions="true"
      :total="store.page.total"
      :page="store.page.page"
      :size="store.page.size"
      :status-options="statusOptions"
      @search="onSearch"
      @reset="onReset"
      @page-change="onPageChange"
      @size-change="onSizeChange"
    >
      <template #cell-status="{ row }">
        <span :class="'status-badge status-' + row.status">{{ store.formatValue(row.status) }}</span>
      </template>
      <template #actions="{ row }">
        <button @click="viewDetail(row)">详情</button>
        <button v-if="['pending_approval', 'active', 'unassigned', 'all_rejected'].includes(row.status)" class="warning" @click="store.adminTeamTaskAction(row.id, 'cancel')">取消</button>
        <button v-if="row.status === 'cancelled'" @click="store.adminTeamTaskAction(row.id, 'restore')">恢复</button>
      </template>
    </DataTable>

    <!-- Detail Modal -->
    <div v-if="showDetail && store.currentDetail" class="modal-overlay" @click.self="closeDetail">
      <div class="detail-modal">
        <div class="modal-header">
          <h2>任务详情</h2>
          <button @click="closeDetail">&times;</button>
        </div>
        <div class="modal-body">
          <div v-if="store.detailLoading" class="loading">加载中...</div>
          <div v-else>
            <div class="detail-grid">
              <div class="detail-item" v-for="(value, key) in store.currentDetail" :key="key">
                <span class="detail-label">{{ key }}</span>
                <span class="detail-value">{{ store.formatValue(value) }}</span>
              </div>
            </div>
            <div v-if="store.currentDetail.assignees && store.currentDetail.assignees.length" class="detail-section">
              <h3>任务执行人</h3>
              <div class="data-table">
                <div class="table-row table-title" style="grid-template-columns: repeat(3, minmax(120px, 1fr));">
                  <strong>用户ID</strong>
                  <strong>状态</strong>
                  <strong>时间</strong>
                </div>
                <div v-for="assignee in store.currentDetail.assignees" :key="assignee.id || assignee.userId" class="table-row" style="grid-template-columns: repeat(3, minmax(120px, 1fr));">
                  <span>{{ assignee.userId || assignee.id }}</span>
                  <span>{{ assignee.status || '-' }}</span>
                  <span>{{ assignee.createdAt || '-' }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
