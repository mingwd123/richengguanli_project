<script setup>
import { onMounted, ref } from 'vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'

const store = useAdminStore()
const showDetail = ref(false)

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
              <div class="detail-item" v-for="(value, key) in store.currentDetail" :key="key">
                <span class="detail-label">{{ key }}</span>
                <span class="detail-value">{{ store.formatValue(value) }}</span>
              </div>
            </div>
            <div v-if="store.currentDetail.members && store.currentDetail.members.length" class="detail-section">
              <h3>团队成员</h3>
              <div class="data-table">
                <div class="table-row table-title" style="grid-template-columns: repeat(3, minmax(120px, 1fr));">
                  <strong>用户ID</strong>
                  <strong>角色</strong>
                  <strong>加入时间</strong>
                </div>
                <div v-for="member in store.currentDetail.members" :key="member.id || member.userId" class="table-row" style="grid-template-columns: repeat(3, minmax(120px, 1fr));">
                  <span>{{ member.userId || member.id }}</span>
                  <span>{{ member.role || '-' }}</span>
                  <span>{{ member.joinedAt || member.createdAt || '-' }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
