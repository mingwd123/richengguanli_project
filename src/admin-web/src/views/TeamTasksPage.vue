<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'
import { useResourceList } from '../composables/useResourceList'

const store = useAdminStore()
const route = useRoute()
const router = useRouter()
const { onSearch, onReset, onPageChange, onSizeChange, onSortChange, refresh } = useResourceList('teamTasks')
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

async function viewDetail(row) {
  router.push({ query: { ...route.query, taskId: row.id } })
}
watch(() => route.query.taskId, async id => {
  showDetail.value = /^\d+$/.test(String(id || ''))
  if (showDetail.value) await store.fetchTeamTaskDetail(id)
}, { immediate: true })

function closeDetail() {
  showDetail.value = false
  store.currentDetail = null
  const query = { ...route.query }; delete query.taskId
  router.replace({ query })
}
</script>

<template>
  <div class="resource-page">
    <header class="topbar">
      <div>
        <h1>团队任务</h1>
        <p>查看所有团队任务。</p>
      </div>
      <button class="primary" @click="refresh">刷新</button>
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
      :keyword="store.searchKeyword"
      :status="store.filterStatus"
      :status-options="statusOptions"
      :date-from="store.filterDateFrom"
      :date-to="store.filterDateTo"
      @search="onSearch"
      @reset="onReset"
      @page-change="onPageChange"
      @size-change="onSizeChange"
      @sort-change="onSortChange"
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
