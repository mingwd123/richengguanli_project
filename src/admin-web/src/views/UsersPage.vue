<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Edit, Lock, Plus } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { useAdminStore } from '../stores/admin'
import DataTable from '../components/DataTable.vue'
import UserWorkspace from '../components/UserWorkspace.vue'
import { useResourceList } from '../composables/useResourceList'
import {
  focusFirstModalElement,
  modalEscapeRequestsClose,
  restoreModalTriggerFocus as focusEditTrigger,
  trapModalTab,
} from '../utils/modalFocus'
import { registrationSourceLabel } from '../utils/registrationSettings'
import { presetRange } from '../utils/analytics'

const store = useAdminStore()
const route = useRoute()
const router = useRouter()
const { onSearch, onReset, onPageChange, onSizeChange, onSortChange, refresh } = useResourceList('users')
const changingStatus = ref(null)
const showDetail = ref(false)
const showCreate = ref(false)
const selectedUserId = ref(null)
const showEdit = ref(false)
const userEditModal = ref(null)
const editEmailInput = ref(null)
const editNicknameInput = ref(null)
let editTriggerElement = null
let editTriggerUserId = ''
const registrationEnabled = computed(() => store.registrationSettings?.registrationEnabled === true)
const registrationStateKnown = computed(() => store.registrationSettings?.registrationEnabled !== undefined)
const registrationStateLabel = computed(() => {
  if (!registrationStateKnown.value) return '状态未知'
  return registrationEnabled.value ? '允许注册' : '已暂停'
})
const registrationDescription = computed(() => {
  if (!registrationStateKnown.value) return '正在读取公开注册状态。'
  return registrationEnabled.value
    ? '新用户可以在用户端自行注册账号。'
    : '用户端已停止接受新账号注册。'
})
const registrationUpdatedAt = computed(() => {
  const value = store.registrationSettings?.updatedAt
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
})

const columns = [
  { key: 'id', label: 'ID' },
  { key: 'phone', label: '手机号' },
  { key: 'email', label: '邮箱' },
  { key: 'nickname', label: '昵称' },
  { key: 'timezone', label: '时区' },
  { key: 'status', label: '状态' },
  { key: 'riskLevel', label: '近 7 天登录风险' },
  { key: 'createdAt', label: '创建时间' },
]

onMounted(async () => {
  await store.fetchRegistrationSettings()
  const userId = Number(route.query.userId)
  if (Number.isInteger(userId) && userId > 0) {
    selectedUserId.value = userId
    showDetail.value = true
  }
})

function refreshPage() {
  return Promise.all([refresh(), store.fetchRegistrationSettings()])
}

function updateRegistrationEnabled(enabled) {
  store.updateRegistrationEnabled(enabled)
}

function viewRisk(row) {
  const [dateFrom, dateTo] = presetRange(7)
  router.push({ path: '/views/security', query: { userId: row.id, dateFrom, dateTo } })
}

async function changeStatus(row) {
  const status = row.status === 'active' ? 'disabled' : 'active'
  let reason
  try {
    reason = (await ElMessageBox.prompt(status === 'disabled' ? '封禁后将立即撤销用户会话，请填写原因。' : '请填写解封原因。',
      status === 'disabled' ? '封禁账号' : '解封账号',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消',
        inputValidator: value => Boolean(value?.trim()) && value.trim().length <= 1000 || '请填写 1 至 1000 字的原因' })).value
  } catch { return }
  changingStatus.value = row.id
  try { await store.setUserStatus(row, status, reason) }
  finally { changingStatus.value = null }
}

async function viewDetail(row) {
  selectedUserId.value = row.id
  showDetail.value = true
  router.push({ query: { ...route.query, userId: row.id } })
}

function closeDetail() {
  showDetail.value = false
  const query = { ...route.query }
  delete query.userId
  router.replace({ query })
}
watch(() => route.query.userId, value => {
  const id = Number(value)
  selectedUserId.value = Number.isInteger(id) && id > 0 ? id : null
  showDetail.value = Boolean(selectedUserId.value)
})
async function createUser() {
  if (await store.createUser()) showCreate.value = false
}

async function focusEditForm() {
  await nextTick()
  const preferred = store.isSuperAdmin ? editEmailInput.value : editNicknameInput.value
  if (preferred && !preferred.disabled) preferred.focus()
  else focusFirstModalElement(userEditModal.value)
}

async function editUser(row, event) {
  editTriggerElement = event?.currentTarget || null
  editTriggerUserId = String(row.id)
  store.beginUserEdit(row)
  showEdit.value = true
  await focusEditForm()
}

async function restoreEditTriggerFocus() {
  await nextTick()
  focusEditTrigger(editTriggerElement, editTriggerUserId)
  editTriggerElement = null
  editTriggerUserId = ''
}

async function closeEdit() {
  if (store.userEditLoading) return false
  showEdit.value = false
  store.resetUserEditForm()
  await restoreEditTriggerFocus()
  return true
}

function handleEditKeydown(event) {
  if (event.key === 'Escape') {
    if (modalEscapeRequestsClose(event, store.userEditLoading)) closeEdit()
    return
  }
  trapModalTab(event, userEditModal.value)
}

async function saveUserEdit() {
  const confirmLoginIdentifierChange = async () => {
    try {
      await ElMessageBox.confirm(
        '修改邮箱或手机号会让该用户退出登录，管理员设置的新邮箱将视为已验证。确认继续吗？',
        '确认修改登录标识',
        {
          type: 'warning',
          confirmButtonText: '继续保存',
          cancelButtonText: '取消',
          closeOnClickModal: false,
        },
      )
      await nextTick()
      userEditModal.value?.focus()
      return true
    } catch {
      return false
    }
  }
  if (!await store.updateUser({ confirmLoginIdentifierChange })) {
    await focusEditForm()
    return
  }
  showEdit.value = false
  store.resetUserEditForm()
  await restoreEditTriggerFocus()
}
</script>

<template>
  <div class="resource-page">
    <header class="topbar">
      <div>
        <h1>用户管理</h1>
        <p>查看和管理所有用户。</p>
      </div>
      <div class="toolbar-actions"><el-button :icon="Plus" type="primary" @click="showCreate = true">创建用户</el-button><button :disabled="store.loading || store.registrationSettingsLoading" @click="refreshPage">刷新</button></div>
    </header>

    <section class="stats">
      <article><span>总数</span><strong>{{ store.stats.total }}</strong></article>
      <article><span>启用</span><strong>{{ store.stats.active }}</strong></article>
      <article><span>禁用</span><strong>{{ store.page.summary?.disabled || 0 }}</strong></article>
    </section>

    <section class="registration-settings-panel" aria-labelledby="registration-settings-title">
      <div class="registration-settings-copy">
        <span class="registration-settings-kicker">账号入口</span>
        <div class="registration-settings-title-row">
          <h2 id="registration-settings-title">新用户注册</h2>
          <span
            class="registration-state-badge"
            :class="{
              'is-enabled': registrationStateKnown && registrationEnabled,
              'is-disabled': registrationStateKnown && !registrationEnabled,
            }"
          >{{ registrationStateLabel }}</span>
        </div>
        <p>{{ registrationDescription }} 该设置不影响管理员手动创建用户或已有用户登录。</p>
        <small v-if="store.registrationSettings">
          配置来源：{{ registrationSourceLabel(store.registrationSettings.source) }}
          <template v-if="registrationUpdatedAt"> · 更新于 {{ registrationUpdatedAt }}</template>
        </small>
      </div>
      <div class="registration-settings-action">
        <div class="registration-switch-row">
          <span>{{ registrationEnabled ? '开放' : '关闭' }}</span>
          <el-switch
            :model-value="registrationEnabled"
            :loading="store.registrationSettingsLoading || store.registrationSettingsUpdating"
            :disabled="!registrationStateKnown || !store.isSuperAdmin || store.registrationSettingsLoading || store.registrationSettingsUpdating"
            inline-prompt
            active-text="开"
            inactive-text="关"
            aria-label="是否允许新用户注册"
            @change="updateRegistrationEnabled"
          />
        </div>
        <small v-if="store.isSuperAdmin">修改后立即对用户端生效</small>
        <small v-else class="readonly-note"><el-icon><Lock /></el-icon>仅超级管理员可修改</small>
      </div>
    </section>

    <el-dialog v-model="showCreate" title="创建用户" width="min(680px, 94vw)" :close-on-click-modal="!store.createLoading" :show-close="!store.createLoading">
      <form class="account-create-form user-account-create-form" novalidate @submit.prevent="createUser">
        <label>
          <span>邮箱</span>
          <input v-model="store.userCreateForm.email" type="email" maxlength="254" inputmode="email" autocomplete="off" placeholder="name@example.com" :disabled="store.createLoading" required />
        </label>
        <label>
          <span>初始密码</span>
          <input v-model="store.userCreateForm.password" type="password" minlength="8" maxlength="72" autocomplete="new-password" placeholder="至少 8 位，含字母和数字" :disabled="store.createLoading" required />
          <small>至少 8 位，且包含字母和数字</small>
        </label>
        <label>
          <span>手机号（可选）</span>
          <input v-model="store.userCreateForm.phone" type="tel" maxlength="11" inputmode="numeric" autocomplete="off" placeholder="中国大陆手机号" :disabled="store.createLoading" />
        </label>
        <label>
          <span>昵称（可选）</span>
          <input v-model="store.userCreateForm.nickname" maxlength="50" autocomplete="off" placeholder="默认 User" :disabled="store.createLoading" />
        </label>
        <label>
          <span>时区</span>
          <input v-model="store.userCreateForm.timezone" autocomplete="off" placeholder="Asia/Shanghai" :disabled="store.createLoading" />
        </label>
        <button class="primary account-create-submit" :disabled="store.createLoading">
          <el-icon><Plus /></el-icon><span>{{ store.createLoading ? '创建中...' : '创建用户' }}</span>
        </button>
      </form>
    </el-dialog>

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
      :status-options="[{ value: 'active', label: '启用' }, { value: 'disabled', label: '禁用' }]"
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
      <template #cell-riskLevel="{ row }">
        <el-button v-if="row.riskCount" link :type="row.riskLevel === 'high' ? 'danger' : 'warning'" @click="viewRisk(row)">
          {{ row.riskLevel === 'high' ? '高风险' : '需关注' }} · {{ row.riskCount }}
        </el-button>
        <span v-else>无异常记录</span>
      </template>
      <template #actions="{ row }">
        <button :data-user-edit-id="row.id" @click="editUser(row, $event)">编辑</button>
        <button :disabled="changingStatus !== null" @click="changeStatus(row)">{{ row.status === 'active' ? '封禁' : '解封' }}</button>
        <button @click="viewDetail(row)">详情</button>
      </template>
    </DataTable>

    <div v-if="showEdit" class="modal-overlay" @click.self="closeEdit">
      <div ref="userEditModal" class="detail-modal user-edit-modal" role="dialog" aria-modal="true" aria-labelledby="user-edit-title" tabindex="-1" @keydown="handleEditKeydown">
        <div class="modal-header">
          <div>
            <h2 id="user-edit-title">编辑用户资料</h2>
            <p>用户 ID {{ store.userEditForm.id }} · 邮箱和手机号至少填写一项</p>
          </div>
          <button type="button" title="关闭" aria-label="关闭编辑用户弹窗" :disabled="store.userEditLoading" @click="closeEdit">&times;</button>
        </div>
        <form class="modal-body user-edit-form" novalidate @submit.prevent="saveUserEdit">
          <div v-if="!store.isSuperAdmin" class="user-edit-scope-note">
            <el-icon><Lock /></el-icon><span>普通管理员只能修改昵称和时区，邮箱与手机号为只读。</span>
          </div>
          <div class="user-edit-fields">
            <label>
              <span>邮箱（可选）</span>
              <input ref="editEmailInput" v-model="store.userEditForm.email" type="email" maxlength="254" inputmode="email" autocomplete="off" placeholder="name@example.com" :disabled="store.userEditLoading || !store.isSuperAdmin" />
            </label>
            <label>
              <span>手机号（可选）</span>
              <input v-model="store.userEditForm.phone" type="tel" maxlength="11" inputmode="numeric" autocomplete="off" placeholder="中国大陆手机号" :disabled="store.userEditLoading || !store.isSuperAdmin" />
            </label>
            <label>
              <span>昵称（可选）</span>
              <input ref="editNicknameInput" v-model="store.userEditForm.nickname" maxlength="50" autocomplete="off" placeholder="用户昵称" :disabled="store.userEditLoading" />
            </label>
            <label>
              <span>时区</span>
              <input v-model="store.userEditForm.timezone" autocomplete="off" placeholder="Asia/Shanghai" :disabled="store.userEditLoading" />
              <small>使用 IANA 时区，例如 Asia/Shanghai</small>
            </label>
          </div>
          <div class="user-edit-actions">
            <button type="button" :disabled="store.userEditLoading" @click="closeEdit">取消</button>
            <button class="primary" :disabled="store.userEditLoading">
              <el-icon><Edit /></el-icon><span>{{ store.userEditLoading ? '保存中...' : '保存修改' }}</span>
            </button>
          </div>
        </form>
      </div>
    </div>

    <UserWorkspace :model-value="showDetail" :user-id="selectedUserId" @update:model-value="closeDetail" />
  </div>
</template>

<style scoped>
.resource-page {
  padding-top: 0;
}
.status-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}
.status-active { background: #e6f7e6; color: #1a7d1a; }
.status-disabled { background: #ffe6e6; color: #cc0000; }
.status-pending { background: #fff3e0; color: #e65100; }
.status-completed { background: #e3f2fd; color: #1565c0; }
.status-cancelled { background: #f3e5f5; color: #7b1fa2; }
.registration-settings-panel {
  min-height: 112px;
  margin-bottom: 14px;
  padding: 16px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border: 1px solid var(--admin-border-soft);
  border-radius: 8px;
  background: var(--admin-surface);
}
.registration-settings-copy { min-width: 0; }
.registration-settings-kicker {
  display: block;
  margin-bottom: 5px;
  color: var(--admin-primary);
  font-size: 10px;
  font-weight: 800;
}
.registration-settings-title-row { display: flex; align-items: center; gap: 9px; }
.registration-settings-title-row h2 { color: var(--admin-text-strong); font-size: 15px; }
.registration-settings-copy p { margin-top: 7px; color: var(--admin-secondary); font-size: 11px; }
.registration-settings-copy > small { display: block; margin-top: 7px; color: var(--admin-muted); font-size: 10px; }
.registration-state-badge {
  padding: 3px 7px;
  border-radius: 5px;
  background: var(--admin-surface-muted);
  color: var(--admin-muted);
  font-size: 10px;
  font-weight: 700;
}
.registration-state-badge.is-enabled { background: var(--admin-success-soft); color: var(--admin-success); }
.registration-state-badge.is-disabled { background: var(--admin-danger-soft); color: var(--admin-danger); }
.registration-settings-action { flex: 0 0 auto; display: grid; justify-items: end; gap: 7px; }
.registration-switch-row { display: flex; align-items: center; gap: 10px; color: var(--admin-text-strong); font-size: 12px; font-weight: 700; }
.registration-settings-action > small { color: var(--admin-muted); font-size: 10px; }
.readonly-note { display: inline-flex; align-items: center; gap: 4px; }
.user-edit-modal {
  width: min(620px, 100%);
  max-height: 80vh;
  max-height: 80dvh;
  display: flex;
  flex-direction: column;
}
.user-edit-modal .modal-header { flex: 0 0 auto; }
.user-edit-modal .modal-header > div { min-width: 0; }
.user-edit-modal .modal-header p { margin-top: 4px; color: var(--admin-muted); font-size: 10px; }
.user-edit-form {
  min-height: 0;
  max-height: none;
  flex: 1 1 auto;
  display: grid;
  gap: 20px;
  overflow: auto;
}
.user-edit-scope-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 10px 12px;
  border: 1px solid var(--admin-border-soft);
  border-radius: 7px;
  background: var(--admin-surface-soft);
  color: var(--admin-muted);
  font-size: 10px;
}
.user-edit-scope-note .el-icon { flex: 0 0 auto; margin-top: 1px; color: var(--admin-primary); }
.user-edit-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; }
.user-edit-fields label { min-width: 0; display: grid; gap: 7px; color: var(--admin-secondary); font-size: 11px; font-weight: 700; }
.user-edit-fields input { width: 100%; }
.user-edit-fields small { color: var(--admin-muted); font-size: 10px; font-weight: 400; }
.user-edit-actions { display: flex; justify-content: flex-end; gap: 9px; padding-top: 15px; border-top: 1px solid var(--admin-border-soft); }

@media (max-width: 680px) {
  .registration-settings-panel { align-items: flex-start; padding: 15px; flex-direction: column; gap: 14px; }
  .registration-settings-action { width: 100%; justify-items: start; }
  .registration-switch-row { width: 100%; justify-content: space-between; }
  .user-edit-fields { grid-template-columns: 1fr; }
  .user-edit-actions { display: grid; grid-template-columns: 1fr 1fr; }
}
</style>
