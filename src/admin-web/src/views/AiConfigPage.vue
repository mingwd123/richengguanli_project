<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ArrowUp,
  CircleCheck,
  CirclePlus,
  Delete,
  Edit,
  Key,
  Lock,
  Refresh,
  VideoPlay,
  WarningFilled,
} from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'
import {
  buildAiConfigPayload,
  buildAiKeyPayload,
  formatAiTestResult,
  moveAiKeyIds,
  validateAiKeyForm,
} from './aiConfigHelpers'

const MAX_KEYS = 10
const store = useAdminStore()
const configForm = ref({ provider: '', modelName: '', apiBaseUrl: '', remark: '' })
const keyDialogVisible = ref(false)
const editingKeyId = ref(null)
const keyForm = ref({ name: '', apiKey: '', enabled: true, remark: '' })
const keyFormError = ref('')

const isEditingKey = computed(() => editingKeyId.value !== null)
const keyDialogTitle = computed(() => isEditingKey.value ? '编辑 API Key' : '新增 API Key')
const keyDialogActionId = computed(() => isEditingKey.value ? `update:${editingKeyId.value}` : 'create')
const keyDialogLoading = computed(() => store.aiKeyActionId === keyDialogActionId.value)
const keyActionRunning = computed(() => store.aiKeyActionId !== null || store.aiTestLoading)
const canAddKey = computed(() => store.aiKeys.length < MAX_KEYS)
const fullTestSummary = computed(() => formatAiTestResult(store.aiTestResult))

function applyConfig(config) {
  configForm.value = {
    provider: config?.provider || '',
    modelName: config?.modelName || '',
    apiBaseUrl: config?.apiBaseUrl || '',
    remark: config?.remark || '',
  }
}

async function refresh() {
  const result = await store.fetchAiConfig()
  if (result) applyConfig(store.aiConfig)
}

onMounted(async () => {
  if (!store.profile) {
    try {
      await store.loadProfile()
    } catch {
      return
    }
  }
  if (store.isSuperAdmin) await refresh()
})

async function saveConfig() {
  const result = await store.updateAiConfig(buildAiConfigPayload(configForm.value))
  if (result) applyConfig(store.aiConfig)
}

async function toggleAiEnabled(enabled) {
  await store.updateAiEnabled(enabled)
}

function resetKeyForm() {
  editingKeyId.value = null
  keyForm.value = { name: '', apiKey: '', enabled: true, remark: '' }
  keyFormError.value = ''
}

function openCreateKey() {
  if (!canAddKey.value) {
    store.notify(`最多可配置 ${MAX_KEYS} 个数据库 Key`, 'warning')
    return
  }
  resetKeyForm()
  keyDialogVisible.value = true
}

function openEditKey(key) {
  editingKeyId.value = key.id
  keyForm.value = {
    name: key.name || '',
    apiKey: '',
    enabled: key.enabled !== false,
    remark: key.remark || '',
  }
  keyFormError.value = ''
  keyDialogVisible.value = true
}

function beforeKeyDialogClose(done) {
  if (!keyDialogLoading.value) done()
}

async function submitKey() {
  keyFormError.value = validateAiKeyForm(keyForm.value, isEditingKey.value)
  if (keyFormError.value) return

  const payload = buildAiKeyPayload(keyForm.value)
  const result = isEditingKey.value
    ? await store.updateAiKey(editingKeyId.value, payload)
    : await store.createAiKey(payload)
  if (result) keyDialogVisible.value = false
}

async function confirmDeleteKey(key) {
  try {
    await ElMessageBox.confirm(
      `确认删除“${key.name}”？`,
      '删除 API Key',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return
  }
  await store.deleteAiKey(key.id)
}

async function toggleKeyEnabled(key, enabled) {
  await store.setAiKeyEnabled(key.id, enabled)
}

async function moveKey(key, direction) {
  const orderedIds = moveAiKeyIds(store.aiKeys, key.id, direction)
  if (!orderedIds) return
  await store.updateAiKeyOrder(orderedIds, store.aiKeyPoolRevision)
}

function testKey(key) {
  store.testAiKey(key.id)
}

function testFullChain() {
  store.testAi()
}

function storedTestLabel(key) {
  if (key.lastTestStatus === 'success') return '上次测试成功'
  if (key.lastTestStatus === 'failed') return key.lastError ? `上次测试失败：${key.lastError}` : '上次测试失败'
  return '未测试'
}
</script>

<template>
  <div v-if="store.isSuperAdmin" class="resource-page ai-config-page">
    <header class="topbar">
      <div>
        <h1>AI 配置</h1>
        <p>管理服务参数、调用凭据和容错优先级。</p>
      </div>
      <el-button :loading="store.aiConfigLoading" @click="refresh">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </header>

    <section v-if="store.aiConfigLoading && !store.aiConfig" class="loading">加载中...</section>

    <template v-else>
      <section class="form-card service-config-section">
        <div class="section-heading">
          <div>
            <h2>服务商配置</h2>
            <span :class="['status-badge', store.aiConfig?.enabled ? 'status-active' : 'status-disabled']">
              {{ store.aiConfig?.enabled ? '已启用' : '已关闭' }}
            </span>
          </div>
          <el-switch
            :model-value="store.aiConfig?.enabled === true"
            :loading="store.aiConfigLoading"
            active-text="启用 AI"
            inactive-text="关闭 AI"
            @change="toggleAiEnabled"
          />
        </div>

        <form class="config-form" @submit.prevent="saveConfig">
          <label>
            <span>服务商</span>
            <input v-model="configForm.provider" placeholder="deepseek" />
          </label>
          <label>
            <span>模型名称</span>
            <input v-model="configForm.modelName" placeholder="deepseek-chat" />
          </label>
          <label class="wide-field">
            <span>API Base URL</span>
            <input v-model="configForm.apiBaseUrl" placeholder="https://api.deepseek.com/v1" />
          </label>
          <label class="wide-field">
            <span>备注</span>
            <textarea v-model="configForm.remark" maxlength="500" placeholder="可选备注信息"></textarea>
          </label>
          <div class="config-form-actions wide-field">
            <el-button type="primary" native-type="submit" :loading="store.aiConfigLoading">保存服务配置</el-button>
          </div>
        </form>
      </section>

      <section class="form-card key-pool-section">
        <div class="section-heading key-pool-heading">
          <div>
            <h2>API Key 池</h2>
            <span class="pool-count">{{ store.aiKeys.length }} / {{ MAX_KEYS }}</span>
          </div>
          <div class="key-pool-actions">
            <el-button :loading="store.aiTestLoading" :disabled="store.aiKeyActionId !== null" @click="testFullChain">
              <el-icon><VideoPlay /></el-icon>
              测试完整链路
            </el-button>
            <el-button type="primary" :disabled="!canAddKey || keyActionRunning" @click="openCreateKey">
              <el-icon><CirclePlus /></el-icon>
              新增 Key
            </el-button>
          </div>
        </div>

        <div class="key-table" role="table" aria-label="API Key 优先级列表">
          <div class="key-row key-table-head" role="row">
            <span>优先级</span>
            <span>名称与凭据</span>
            <span>状态</span>
            <span>备注</span>
            <span>操作</span>
          </div>

          <div v-for="(key, index) in store.aiKeys" :key="key.id" class="key-row" role="row">
            <div class="priority-cell" data-label="优先级">
              <strong>{{ key.priority || index + 1 }}</strong>
            </div>
            <div class="key-identity" data-label="名称与凭据">
              <div><el-icon><Key /></el-icon><strong>{{ key.name }}</strong></div>
              <code>{{ key.apiKeyMasked || '已加密保存' }}</code>
              <small
                v-if="store.aiKeyTestResults[key.id]"
                :class="store.aiKeyTestResults[key.id].ok ? 'test-success' : 'test-failed'"
              >{{ formatAiTestResult(store.aiKeyTestResults[key.id]) }}</small>
              <small
                v-else
                :class="key.lastTestStatus === 'success' ? 'test-success' : key.lastTestStatus === 'failed' ? 'test-failed' : 'test-untested'"
                :title="key.lastTestedAt || ''"
              >{{ storedTestLabel(key) }}</small>
            </div>
            <div class="status-cell" data-label="状态">
              <el-switch
                :model-value="key.enabled"
                :loading="store.aiKeyActionId === `enabled:${key.id}`"
                :disabled="keyActionRunning && store.aiKeyActionId !== `enabled:${key.id}`"
                :aria-label="`${key.name} 启用状态`"
                @change="value => toggleKeyEnabled(key, value)"
              />
              <span>{{ key.enabled ? '启用' : '停用' }}</span>
            </div>
            <div class="key-remark" data-label="备注">{{ key.remark || '-' }}</div>
            <div class="key-actions" data-label="操作">
              <el-button
                text circle size="small" title="提高优先级" aria-label="提高优先级"
                :disabled="index === 0 || keyActionRunning"
                @click="moveKey(key, 'up')"
              ><el-icon><ArrowUp /></el-icon></el-button>
              <el-button
                text circle size="small" title="降低优先级" aria-label="降低优先级"
                :disabled="index === store.aiKeys.length - 1 || keyActionRunning"
                @click="moveKey(key, 'down')"
              ><el-icon><ArrowDown /></el-icon></el-button>
              <el-button
                text circle size="small" title="测试此 Key" aria-label="测试此 Key"
                :loading="store.aiKeyActionId === `test:${key.id}`"
                :disabled="keyActionRunning && store.aiKeyActionId !== `test:${key.id}`"
                @click="testKey(key)"
              ><el-icon><VideoPlay /></el-icon></el-button>
              <el-button
                text circle size="small" title="编辑" aria-label="编辑"
                :disabled="keyActionRunning"
                @click="openEditKey(key)"
              ><el-icon><Edit /></el-icon></el-button>
              <el-button
                text circle size="small" type="danger" title="删除" aria-label="删除"
                :loading="store.aiKeyActionId === `delete:${key.id}`"
                :disabled="keyActionRunning && store.aiKeyActionId !== `delete:${key.id}`"
                @click="confirmDeleteKey(key)"
              ><el-icon><Delete /></el-icon></el-button>
            </div>
          </div>

          <div v-if="!store.aiKeys.length" class="empty-key-row">暂无数据库 Key</div>

          <div class="key-row environment-key-row" role="row">
            <div class="priority-cell" data-label="优先级"><strong>最后</strong></div>
            <div class="key-identity" data-label="名称与凭据">
              <div><el-icon><Key /></el-icon><strong>环境变量兜底</strong><span class="readonly-tag">只读</span></div>
              <code>{{ store.aiEnvironmentFallback.apiKeyMasked || 'AI_API_KEY' }}</code>
            </div>
            <div class="status-cell" data-label="状态">
              <span :class="['status-badge', store.aiEnvironmentFallback.configured ? 'status-active' : 'status-disabled']">
                {{ store.aiEnvironmentFallback.configured ? '已配置' : '未配置' }}
              </span>
            </div>
            <div class="key-remark" data-label="备注">AI_API_KEY</div>
            <div class="locked-action" data-label="操作"><el-icon><Lock /></el-icon><span>只读</span></div>
          </div>
        </div>

        <div v-if="store.aiTestResult" :class="['chain-test-result', store.aiTestResult.ok ? 'success' : 'failed']">
          <el-icon><CircleCheck v-if="store.aiTestResult.ok" /><WarningFilled v-else /></el-icon>
          <div>
            <strong>{{ fullTestSummary }}</strong>
            <p v-if="store.aiTestResult.rawText">{{ store.aiTestResult.rawText }}</p>
          </div>
        </div>
      </section>
    </template>

    <el-dialog
      v-model="keyDialogVisible"
      :title="keyDialogTitle"
      width="520px"
      append-to-body
      :close-on-click-modal="false"
      :before-close="beforeKeyDialogClose"
      @closed="resetKeyForm"
    >
      <form class="key-dialog-form" @submit.prevent="submitKey">
        <label>
          <span>Key 名称</span>
          <input v-model="keyForm.name" maxlength="100" autocomplete="off" placeholder="例如：主线路" />
        </label>
        <label>
          <span>{{ isEditingKey ? '新 API Key（可选）' : 'API Key' }}</span>
          <input
            v-model="keyForm.apiKey"
            type="password"
            autocomplete="new-password"
            :placeholder="isEditingKey ? '保留当前加密 Key' : 'sk-...'"
          />
        </label>
        <label>
          <span>备注</span>
          <textarea v-model="keyForm.remark" maxlength="500" placeholder="可选备注信息"></textarea>
        </label>
        <div class="key-enabled-field">
          <span>启用状态</span>
          <el-switch v-model="keyForm.enabled" active-text="启用" inactive-text="停用" />
        </div>
        <p v-if="keyFormError" class="form-error">{{ keyFormError }}</p>
      </form>
      <template #footer>
        <el-button :disabled="keyDialogLoading" @click="keyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="keyDialogLoading" @click="submitKey">
          {{ isEditingKey ? '保存' : '添加' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ai-config-page { max-width: 1180px; margin: 0 auto; }
.form-card { margin-top: 16px; }
.section-heading { min-height: 38px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.section-heading > div:first-child { display: flex; align-items: center; gap: 10px; min-width: 0; }
.section-heading h2 { color: var(--admin-text-strong); font-size: 16px; }
.pool-count, .readonly-tag { display: inline-flex; align-items: center; min-height: 22px; padding: 0 7px; border-radius: 5px; background: var(--admin-surface-muted); color: var(--admin-muted); font-size: 11px; font-weight: 700; }

.config-form { margin-top: 18px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; }
.config-form label, .key-dialog-form label { display: grid; gap: 7px; min-width: 0; }
.config-form label > span, .key-dialog-form label > span, .key-enabled-field > span { color: var(--admin-secondary); font-size: 12px; font-weight: 700; }
.config-form input, .config-form textarea, .key-dialog-form input, .key-dialog-form textarea { width: 100%; }
.config-form textarea, .key-dialog-form textarea { min-height: 72px; }
.wide-field { grid-column: 1 / -1; }
.config-form-actions { display: flex; justify-content: flex-end; }

.key-pool-heading { align-items: flex-start; }
.key-pool-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.key-table { margin-top: 16px; overflow: hidden; border: 1px solid var(--admin-border-soft); border-radius: 8px; }
.key-row { min-height: 66px; display: grid; grid-template-columns: 72px minmax(210px, 1.5fr) 112px minmax(130px, 1fr) 190px; align-items: center; gap: 12px; padding: 10px 14px; border-bottom: 1px solid var(--admin-border-soft); color: var(--admin-secondary); font-size: 12px; }
.key-row:last-child { border-bottom: 0; }
.key-table-head { min-height: 40px; background: var(--admin-surface-muted); color: var(--admin-text-strong); font-size: 11px; font-weight: 700; }
.priority-cell strong { display: inline-grid; min-width: 28px; min-height: 28px; place-items: center; border-radius: 6px; background: var(--admin-primary-soft); color: var(--admin-primary); font-size: 12px; }
.key-identity { min-width: 0; display: grid; gap: 5px; }
.key-identity > div { min-width: 0; display: flex; align-items: center; gap: 6px; }
.key-identity strong { overflow: hidden; color: var(--admin-text-strong); text-overflow: ellipsis; white-space: nowrap; }
.key-identity code { width: fit-content; max-width: 100%; overflow: hidden; padding: 2px 6px; border-radius: 4px; color: var(--admin-primary); text-overflow: ellipsis; white-space: nowrap; }
.key-identity small { overflow-wrap: anywhere; font-size: 11px; }
.test-success { color: var(--admin-success); }
.test-failed, .form-error { color: var(--admin-danger); }
.test-untested { color: var(--admin-muted); }
.status-cell { display: flex; align-items: center; gap: 7px; }
.status-cell > span:not(.status-badge) { color: var(--admin-muted); }
.key-remark { min-width: 0; overflow-wrap: anywhere; color: var(--admin-muted); }
.key-actions { display: flex; align-items: center; justify-content: flex-end; gap: 2px; }
.key-actions .el-button + .el-button { margin-left: 0; }
.locked-action { display: flex; align-items: center; justify-content: flex-end; gap: 5px; color: var(--admin-muted); }
.environment-key-row { background: color-mix(in srgb, var(--admin-surface-muted) 45%, var(--admin-surface)); }
.environment-key-row .priority-cell strong { background: var(--admin-blue-soft); color: var(--admin-blue); }
.empty-key-row { padding: 28px 16px; border-bottom: 1px solid var(--admin-border-soft); color: var(--admin-muted); text-align: center; font-size: 12px; }

.chain-test-result { margin-top: 14px; padding: 12px 14px; display: flex; align-items: flex-start; gap: 9px; border: 1px solid; border-radius: 7px; }
.chain-test-result.success { border-color: color-mix(in srgb, var(--admin-success) 28%, var(--admin-border)); background: var(--admin-success-soft); color: var(--admin-success); }
.chain-test-result.failed { border-color: color-mix(in srgb, var(--admin-danger) 28%, var(--admin-border)); background: var(--admin-danger-soft); color: var(--admin-danger); }
.chain-test-result .el-icon { flex: 0 0 auto; margin-top: 2px; }
.chain-test-result strong { font-size: 12px; }
.chain-test-result p { margin-top: 4px; color: var(--admin-secondary); font-size: 11px; overflow-wrap: anywhere; }

.key-dialog-form { display: grid; gap: 16px; }
.key-enabled-field { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.form-error { font-size: 12px; }

@media (max-width: 980px) {
  .key-table-head { display: none; }
  .key-row { grid-template-columns: 76px minmax(0, 1fr) auto; align-items: start; }
  .priority-cell { grid-row: 1 / span 2; }
  .key-identity { grid-column: 2; }
  .status-cell { grid-column: 3; grid-row: 1; justify-content: flex-end; }
  .key-remark { grid-column: 2 / -1; }
  .key-actions, .locked-action { grid-column: 2 / -1; justify-content: flex-start; }
}

@media (max-width: 640px) {
  .section-heading, .key-pool-heading { align-items: stretch; flex-direction: column; }
  .key-pool-actions { justify-content: stretch; }
  .key-pool-actions .el-button { flex: 1; margin-left: 0; }
  .config-form { grid-template-columns: 1fr; }
  .wide-field { grid-column: auto; }
  .key-row { grid-template-columns: 58px minmax(0, 1fr); gap: 9px; padding: 12px; }
  .priority-cell { grid-column: 1; grid-row: 1; }
  .key-identity { grid-column: 2; grid-row: 1; }
  .status-cell, .key-remark, .key-actions, .locked-action { grid-column: 2; grid-row: auto; justify-content: flex-start; }
}
</style>
