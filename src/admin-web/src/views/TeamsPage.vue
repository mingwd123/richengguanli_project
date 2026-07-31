<script setup>
import { computed, onMounted, ref } from 'vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'

const store = useAdminStore()
const showDetail = ref(false)
const detailFields = computed(() => Object.entries(store.currentDetail || {}).filter(([key]) => !['members', 'recentTasks'].includes(key)))
const fieldLabels = {
  id: '团队 ID', name: '团队名称', inviteCode: '邀请码', inviteCodeExpireAt: '邀请码有效期', ownerId: '拥有者 ID',
  status: '状态', createdAt: '创建时间', memberCount: '活跃成员', taskCount: '任务总数', activeTaskCount: '活跃任务'
}

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'name', label: '名称' },
  { key: 'inviteCode', label: '邀请码' },
  { key: 'ownerId', label: '拥有者ID' },
  { key: 'status', label: '状态' },
  { key: 'createdAt', label: '创建时间' },
]

onMounted(async () => {
  store.activeResource = 'teams'
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
  await store.fetchTeamDetail(row.id)
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
        <h1>团队管理</h1>
        <p>查看和管理所有团队。</p>
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
      </template>
    </DataTable>

    <!-- Detail Modal -->
    <div v-if="showDetail && store.currentDetail" class="modal-overlay" @click.self="closeDetail">
      <div class="detail-modal">
        <div class="modal-header">
          <h2>团队详情</h2>
          <button @click="closeDetail">&times;</button>
        </div>
        <div class="modal-body">
          <div v-if="store.detailLoading" class="loading">加载中...</div>
          <div v-else>
            <div class="detail-grid">
              <div class="detail-item" v-for="([key, value]) in detailFields" :key="key">
                <span class="detail-label">{{ fieldLabels[key] || key }}</span>
                <span class="detail-value">{{ store.formatValue(value) }}</span>
              </div>
            </div>
            <div v-if="store.currentDetail.members && store.currentDetail.members.length" class="detail-section">
              <h3>团队成员</h3>
              <div class="data-table">
                <div class="table-row table-title" style="grid-template-columns: 80px minmax(120px, 1fr) 140px 100px 100px 170px;">
                  <strong>用户ID</strong>
                  <strong>成员</strong>
                  <strong>手机号</strong>
                  <strong>角色</strong>
                  <strong>状态</strong>
                  <strong>加入时间</strong>
                </div>
                <div v-for="member in store.currentDetail.members" :key="member.id || member.userId" class="table-row" style="grid-template-columns: 80px minmax(120px, 1fr) 140px 100px 100px 170px;">
                  <span>{{ member.userId || member.id }}</span>
                  <span>{{ member.nickname || '-' }}</span>
                  <span>{{ member.phone || '-' }}</span>
                  <span>{{ member.role || '-' }}</span>
                  <span :class="'status-badge status-' + member.status">{{ store.formatValue(member.status) }}</span>
                  <span>{{ member.joinedAt || member.createdAt || '-' }}</span>
                </div>
              </div>
            </div>
            <div v-if="store.currentDetail.recentTasks && store.currentDetail.recentTasks.length" class="detail-section">
              <h3>最近任务</h3>
              <div class="data-table">
                <div class="table-row table-title" style="grid-template-columns: 80px minmax(180px, 1fr) 110px 170px;"><strong>ID</strong><strong>标题</strong><strong>状态</strong><strong>截止时间</strong></div>
                <div v-for="task in store.currentDetail.recentTasks" :key="task.id" class="table-row" style="grid-template-columns: 80px minmax(180px, 1fr) 110px 170px;">
                  <span>{{ task.id }}</span><span>{{ task.title }}</span><span :class="'status-badge status-' + task.status">{{ store.formatValue(task.status) }}</span><span>{{ task.deadlineTime || '-' }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
