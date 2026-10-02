<script setup>
import { Plus } from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'
import { useResourceList } from '../composables/useResourceList'

const store = useAdminStore()
const { onSearch, onReset, onPageChange, onSizeChange, onSortChange, refresh } = useResourceList('adminUsers')

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'username', label: '用户名' },
  { key: 'role', label: '角色' },
  { key: 'status', label: '状态' },
  { key: 'lastLoginAt', label: '最后登录' },
  { key: 'lastLoginIp', label: '登录IP' },
  { key: 'createdAt', label: '创建时间' },
]

function roleLabel(role) {
  return role === 'super_admin' ? '超级管理员' : role === 'admin' ? '普通管理员' : role
}
</script>

<template>
  <div v-if="store.isSuperAdmin" class="resource-page">
    <header class="topbar">
      <div>
        <h1>管理员账号</h1>
        <p>管理后台管理员账号。</p>
      </div>
      <button class="primary" @click="refresh">刷新</button>
    </header>

    <section class="stats">
      <article><span>总数</span><strong>{{ store.stats.total }}</strong></article>
      <article><span>启用</span><strong>{{ store.stats.active }}</strong></article>
      <article><span>禁用</span><strong>{{ store.page.summary?.disabled || 0 }}</strong></article>
    </section>

    <section class="create-panel account-create-panel">
      <div class="account-create-heading">
        <div>
          <h2>创建普通管理员</h2>
          <p>新账号创建后可登录后台并创建用户。</p>
        </div>
        <span class="role-badge">普通管理员</span>
      </div>
      <form class="account-create-form admin-account-create-form" novalidate @submit.prevent="store.createAdminUser">
        <label>
          <span>管理员账号</span>
          <input v-model="store.adminCreateForm.username" maxlength="50" placeholder="请输入管理员账号" autocomplete="off" :disabled="store.createLoading" required />
        </label>
        <label>
          <span>初始密码</span>
          <input v-model="store.adminCreateForm.password" placeholder="至少 8 位，含字母和数字" type="password" minlength="8" maxlength="72" autocomplete="new-password" :disabled="store.createLoading" required />
          <small>至少 8 位，且包含字母和数字</small>
        </label>
        <button class="primary account-create-submit" :disabled="store.createLoading">
          <el-icon><Plus /></el-icon><span>{{ store.createLoading ? '创建中...' : '创建普通管理员' }}</span>
        </button>
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
      :keyword="store.searchKeyword"
      :status="store.filterStatus"
      :date-from="store.filterDateFrom"
      :date-to="store.filterDateTo"
      :status-options="[{ value: 'active', label: '启用' }, { value: 'disabled', label: '禁用' }]"
      @search="onSearch"
      @reset="onReset"
      @page-change="onPageChange"
      @size-change="onSizeChange"
      @sort-change="onSortChange"
    >
      <template #cell-status="{ row }">
        <span :class="'status-badge status-' + row.status">{{ store.formatValue(row.status) }}</span>
      </template>
      <template #cell-role="{ row }">
        {{ roleLabel(row.role) }}
      </template>
      <template #actions="{ row }">
        <button v-if="row.id === store.profile?.id" disabled title="不能禁用当前登录账号">当前账号</button>
        <button v-else-if="row.status !== 'active'" @click="store.setAdminUserStatus(row, 'active')">启用</button>
        <button v-else @click="store.setAdminUserStatus(row, 'disabled')">禁用</button>
      </template>
    </DataTable>
  </div>
</template>
