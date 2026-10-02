<script setup>
import { ref } from 'vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'
import { useResourceList } from '../composables/useResourceList'

const store = useAdminStore()
const { onSearch, onReset, onPageChange, onSizeChange, onSortChange, refresh } = useResourceList('reminders')
const showDetail = ref(false)
const statusOptions = [
  { value: 'pending', label: '待发送' },
  { value: 'paused', label: '已暂停' },
  { value: 'sent', label: '已发送' },
  { value: 'cancelled', label: '已取消' },
  { value: 'failed', label: '发送失败' },
]

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'userId', label: '用户ID' },
  { key: 'targetType', label: '目标类型' },
  { key: 'targetId', label: '目标ID' },
  { key: 'remindAt', label: '提醒时间' },
  { key: 'status', label: '状态' },
  { key: 'createdAt', label: '创建时间' },
]

async function viewDetail(row) {
  await store.fetchReminderDetail(row.id)
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
        <h1>提醒记录</h1>
        <p>查看所有提醒记录。</p>
      </div>
      <button class="primary" @click="refresh">刷新</button>
    </header>

    <section class="stats">
      <article><span>总数</span><strong>{{ store.stats.total }}</strong></article>
      <article><span>已发送</span><strong>{{ store.page.summary?.sent || 0 }}</strong></article>
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
      :date-from="store.filterDateFrom"
      :date-to="store.filterDateTo"
      :status-options="statusOptions"
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
      </template>
    </DataTable>

    <!-- Detail Modal -->
    <div v-if="showDetail && store.currentDetail" class="modal-overlay" @click.self="closeDetail">
      <div class="detail-modal">
        <div class="modal-header">
          <h2>提醒详情</h2>
          <button @click="closeDetail">&times;</button>
        </div>
        <div class="modal-body">
          <div v-if="store.detailLoading" class="loading">加载中...</div>
          <div v-else class="detail-grid">
            <div class="detail-item" v-for="(value, key) in store.currentDetail" :key="key">
              <span class="detail-label">{{ key }}</span>
              <span class="detail-value">{{ store.formatValue(value) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
