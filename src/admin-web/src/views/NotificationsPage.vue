<script setup>
import { ref } from 'vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'
import { useResourceList } from '../composables/useResourceList'

const store = useAdminStore()
const { onSearch, onReset, onPageChange, onSizeChange, onSortChange, refresh } = useResourceList('notifications')
const showDetail = ref(false)
const statusOptions = [
  { value: 'unread', label: '未读' },
  { value: 'read', label: '已读' },
]

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'userId', label: '用户ID' },
  { key: 'type', label: '类型' },
  { key: 'title', label: '标题' },
  { key: 'relatedType', label: '关联类型' },
  { key: 'relatedId', label: '关联ID' },
  { key: 'isRead', label: '已读' },
  { key: 'createdAt', label: '创建时间' },
]

async function viewDetail(row) {
  await store.fetchNotificationDetail(row.id)
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
        <h1>通知记录</h1>
        <p>查看所有通知记录。</p>
      </div>
      <button class="primary" @click="refresh">刷新</button>
    </header>

    <section class="stats">
      <article><span>总数</span><strong>{{ store.stats.total }}</strong></article>
      <article><span>已读</span><strong>{{ store.page.summary?.read || 0 }}</strong></article>
      <article><span>未读</span><strong>{{ store.page.summary?.unread || 0 }}</strong></article>
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
      <template #cell-isRead="{ row }">
        {{ row.isRead ? '是' : '否' }}
      </template>
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
          <h2>通知详情</h2>
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
