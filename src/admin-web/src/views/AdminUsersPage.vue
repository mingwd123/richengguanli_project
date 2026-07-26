<script setup>
import { onMounted, ref } from 'vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'

const store = useAdminStore()
const showDetail = ref(false)

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'username', label: '用户名' },
  { key: 'role', label: '角色' },
  { key: 'status', label: '状态' },
  { key: 'lastLoginAt', label: '最后登录' },
  { key: 'lastLoginIp', label: '登录IP' },
  { key: 'createdAt', label: '创建时间' },
]

onMounted(async () => {
  store.activeResource = 'adminUsers'
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
  store.currentDetail = row
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
        <h1>管理员账号</h1>
        <p>管理后台管理员账号。</p>
      </div>
      <button class="primary" @click="store.fetchList()">刷新</button>
    </header>

    <section class="stats">
      <article><span>总数</span><strong>{{ store.stats.total }}</strong></article>
      <article><span>启用</span><strong>{{ store.stats.active }}</strong></article>
      <article><span>待处理</span><strong>{{ store.stats.pending }}</strong></article>
    </section>

    <section class="create-panel">
      <h2>创建管理员</h2>
      <form class="create-form" @submit.prevent="store.createAdminUser">
        <input v-model="store.adminCreateForm.username" placeholder="管理员账号" autocomplete="off" />
        <input v-model="store.adminCreateForm.password" placeholder="密码" type="password" autocomplete="new-password" />
        <input v-model="store.adminCreateForm.role" placeholder="角色" />
        <button class="primary" :disabled="store.loading">创建</button>
      </form>
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
        <button v-if="row.status !== 'active'" @click="store.setAdminUserStatus(row, 'active')">启用</button>
        <button v-else @click="store.setAdminUserStatus(row, 'disabled')">禁用</button>
      </template>
    </DataTable>
  </div>
</template>
