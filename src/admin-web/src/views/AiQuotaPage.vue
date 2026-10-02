<script setup>
import { computed, onMounted, ref } from 'vue'
import { Plus, Delete, Refresh, CircleCheck } from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'

const store = useAdminStore()
const loading = ref(false)
const saving = ref(false)
const loaded = ref(false)
const form = ref({ enabled: false, globalDailyLimit: 10000, userDailyLimit: 100, teamDailyLimit: 1000, warningPercent: 80, revision: 0, prices: [] })
const overrideRows = ref([])
const usageRows = ref([])
const usageTotal = ref(0)
const usagePage = ref(1)
const usageLoading = ref(false)
const error = ref('')
const hasInvalidPrice = computed(() => {
  const names = form.value.prices.map(row => row.modelName.trim().toLowerCase())
  return names.some(name => !name) || new Set(names).size !== names.length || form.value.prices.some(row =>
    [row.inputPerMillion, row.outputPerMillion].some(value => typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1000000))
})
async function load() {
  if (loading.value || saving.value) return
  loading.value = true
  error.value = ''
  try {
    const data = await store.request('/admin/ai/quota')
    form.value = { ...data, prices: (data.prices || []).map(row => ({ ...row })) }
    overrideRows.value = (data.overrides || []).map(row => ({ ...row }))
    usageRows.value = data.usage?.items || []
    usageTotal.value = data.usage?.total || 0
    usagePage.value = 1
    loaded.value = true
  } catch (reason) { error.value = reason.message || '配置加载失败'; loaded.value = false }
  finally { loading.value = false }
}
function addPrice() { form.value.prices.push({ modelName: '', inputPerMillion: 0, outputPerMillion: 0 }) }
function removePrice(index) { form.value.prices.splice(index, 1) }
function addOverride() { overrideRows.value.push({ scopeType: 'user', subjectId: '', dailyLimit: form.value.userDailyLimit, enabled: true }) }
function removeOverride(index) { overrideRows.value.splice(index, 1) }
async function loadUsage(page) {
  usageLoading.value = true
  try {
    const data = await store.request(`/admin/ai/quota/usage?page=${page}&size=20`)
    usageRows.value = data.items
    usageTotal.value = data.total
    usagePage.value = page
  } catch (reason) { store.notify(reason.message, 'error') }
  finally { usageLoading.value = false }
}
async function save() {
  if (!loaded.value || loading.value || saving.value) return
  error.value = ''
  if (hasInvalidPrice.value) { error.value = '请检查模型名称是否重复，以及单价是否为 0 至 1,000,000 的数字'; return }
  if (![form.value.globalDailyLimit, form.value.userDailyLimit, form.value.teamDailyLimit].every(value => Number.isInteger(Number(value)) && Number(value) > 0)) {
    error.value = '额度必须是大于 0 的整数'; return
  }
  if (!Number.isInteger(Number(form.value.warningPercent)) || Number(form.value.warningPercent) < 1 || Number(form.value.warningPercent) > 100) {
    error.value = '预警阈值必须是 1 至 100 的整数'; return
  }
  if (overrideRows.value.some(row => !Number.isInteger(Number(row.subjectId)) || Number(row.subjectId) <= 0
    || !Number.isInteger(Number(row.dailyLimit)) || Number(row.dailyLimit) <= 0)) {
    error.value = '额度覆盖必须填写有效的主体 ID 和正整数上限'; return
  }
  saving.value = true
  try {
    const data = await store.request('/admin/ai/quota', { method: 'PUT', body: JSON.stringify({
      ...form.value,
      warningPercent: Number(form.value.warningPercent),
      overrides: overrideRows.value.map(row => ({ ...row, subjectId: Number(row.subjectId), dailyLimit: Number(row.dailyLimit) })),
    }) })
    form.value = { ...data, prices: (data.prices || []).map(row => ({ ...row })) }
    overrideRows.value = (data.overrides || []).map(row => ({ ...row }))
    usageRows.value = data.usage?.items || []
    usageTotal.value = data.usage?.total || 0
    usagePage.value = 1
    store.notify('AI 配额已保存')
  } catch (reason) {
    error.value = Number(reason.code) === 409 ? '规则已被其他管理员更新，请刷新后重新修改。' : reason.message || '保存失败'
    if (Number(reason.code) === 409) loaded.value = false
  }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <div class="analytics-page">
    <header class="topbar"><div><span class="analytics-eyebrow">AI 成本</span><h1>配额与成本规则</h1><p>超限会在真正调用 AI 前拒绝，并记录告警；成本仅用于可见性，不包含收费。</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="analytics-alert" />
    <el-skeleton v-if="loading" :rows="8" animated />
    <template v-else-if="loaded">
      <fieldset class="quota-fields" :disabled="saving">
      <section class="quota-form">
        <div class="quota-form-heading"><div><h2>每日调用上限</h2><p>UTC 自然日；团队调用仅计入所选团队，失败请求也占用已预留额度。</p></div><el-switch v-model="form.enabled" inline-prompt active-text="开" inactive-text="关" /></div>
        <div class="quota-input-grid">
          <label><span>全局上限</span><el-input-number v-model="form.globalDailyLimit" :min="1" :max="100000000" :step="100" controls-position="right" /></label>
          <label><span>单用户上限</span><el-input-number v-model="form.userDailyLimit" :min="1" :max="100000000" :step="10" controls-position="right" /></label>
          <label><span>单团队上限</span><el-input-number v-model="form.teamDailyLimit" :min="1" :max="100000000" :step="100" controls-position="right" /></label>
          <label><span>接近上限预警 (%)</span><el-input-number v-model="form.warningPercent" :min="1" :max="100" controls-position="right" /></label>
          <label><span>今日全局已用</span><strong class="quota-readonly">{{ form.todayCalls?.toLocaleString() || 0 }}</strong></label>
        </div>
      </section>
      <section class="quota-form">
        <div class="quota-form-heading"><div><h2>主体额度覆盖</h2><p>仅对指定用户或团队生效；个人调用不会计入团队额度。</p></div><el-button :icon="Plus" :disabled="overrideRows.length >= 200 || saving" @click="addOverride">添加覆盖</el-button></div>
        <el-table :data="overrideRows" empty-text="未配置单独额度">
          <el-table-column label="范围" width="130"><template #default="{ row }"><el-select v-model="row.scopeType"><el-option value="user" label="用户" /><el-option value="team" label="团队" /></el-select></template></el-table-column>
          <el-table-column label="主体 ID" width="150"><template #default="{ row }"><el-input-number v-model="row.subjectId" :min="1" controls-position="right" /></template></el-table-column>
          <el-table-column label="每日上限" width="170"><template #default="{ row }"><el-input-number v-model="row.dailyLimit" :min="1" :max="100000000" controls-position="right" /></template></el-table-column>
          <el-table-column label="启用" width="90"><template #default="{ row }"><el-switch v-model="row.enabled" /></template></el-table-column>
          <el-table-column label="操作" width="80"><template #default="{ $index }"><el-button text type="danger" :icon="Delete" aria-label="删除额度覆盖" @click="removeOverride($index)" /></template></el-table-column>
        </el-table>
      </section>
      <section class="quota-form">
        <div class="quota-form-heading"><div><h2>今日主体用量</h2><p>按 UTC 自然日展示全局、用户和团队已用次数。</p></div></div>
        <el-table :data="usageRows" v-loading="usageLoading" empty-text="今日尚无用量记录">
          <el-table-column label="范围" min-width="100"><template #default="{ row }">{{ row.scopeType === 'global' ? '全局' : row.scopeType === 'team' ? '团队' : '用户' }}</template></el-table-column>
          <el-table-column prop="subjectId" label="主体 ID" width="110" />
          <el-table-column prop="calls" label="已用次数" width="110" />
          <el-table-column prop="dailyLimit" label="当前上限" width="110" />
          <el-table-column label="使用率" width="110"><template #default="{ row }">{{ row.dailyLimit ? `${Math.round(row.calls * 1000 / row.dailyLimit) / 10}%` : '--' }}</template></el-table-column>
        </el-table>
        <el-pagination :current-page="usagePage" :page-size="20" :total="usageTotal" layout="total, prev, pager, next" :disabled="usageLoading" @current-change="loadUsage" />
      </section>
      <section class="quota-form">
        <div class="quota-form-heading"><div><h2>模型单价</h2><p>单位：CNY / 1,000,000 tokens。新单价只影响后续调用。</p></div><el-button :icon="Plus" :disabled="form.prices.length >= 50 || saving" @click="addPrice">添加模型</el-button></div>
        <el-table :data="form.prices" empty-text="尚未配置模型单价">
          <el-table-column label="模型名称" min-width="200"><template #default="{ row }"><el-input v-model="row.modelName" maxlength="100" placeholder="deepseek-chat" /></template></el-table-column>
          <el-table-column label="输入单价" min-width="160"><template #default="{ row }"><el-input-number v-model="row.inputPerMillion" :min="0" :precision="6" :step="0.1" controls-position="right" /></template></el-table-column>
          <el-table-column label="输出单价" min-width="160"><template #default="{ row }"><el-input-number v-model="row.outputPerMillion" :min="0" :precision="6" :step="0.1" controls-position="right" /></template></el-table-column>
          <el-table-column label="操作" width="80"><template #default="{ $index }"><el-button text type="danger" :icon="Delete" aria-label="删除模型单价" @click="removePrice($index)" /></template></el-table-column>
        </el-table>
      </section>
      </fieldset>
      <div class="quota-actions"><span>规则版本 {{ form.revision }} · 保存前会检查是否被其他管理员更新</span><el-button type="primary" :icon="CircleCheck" :loading="saving" @click="save">保存规则</el-button></div>
    </template>
  </div>
</template>
