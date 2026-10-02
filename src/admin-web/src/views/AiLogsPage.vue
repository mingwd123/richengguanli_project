<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh, Search, RefreshLeft } from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'
import { columnLabels, dataLabels, queryPage } from '../utils/analytics'

const store = useAdminStore()
const route = useRoute()
const router = useRouter()
const filters = reactive({ userId: '', featureType: '', status: '', dateFrom: '', dateTo: '' })
const detailLog = ref(null)
const detailLoading = ref(false)
const showDetail = computed(() => Boolean(route.query.logId))
const detailFields = computed(() => Object.entries(detailLog.value || {}).filter(([key]) => !['inputText', 'outputText', 'errorMessage'].includes(key)))
const features = ['schedule_parse', 'team_task_breakdown', 'daily_plan', 'task_description_optimize']
let detailSequence = 0

function search(page = 1, size = store.page.size) {
  const query = { ...route.query, page: String(page), size: String(size) }
  for (const [key, value] of Object.entries(filters)) { if (value) query[key] = value; else delete query[key] }
  if (JSON.stringify(query) === JSON.stringify(route.query)) load()
  else router.push({ query })
}
function reset() { Object.keys(filters).forEach(key => { filters[key] = '' }); search() }
function load() {
  for (const key of Object.keys(filters)) filters[key] = String(route.query[key] || '')
  return store.fetchAiUsageLogs({ ...filters, page: queryPage(route.query.page), size: Number(route.query.size) || 20 })
}
async function loadDetail(id) {
  const sequence = ++detailSequence
  detailLog.value = null
  if (!id) return
  detailLoading.value = true
  try {
    const data = await store.request(`/admin/ai/usage-logs/${encodeURIComponent(id)}`)
    if (sequence === detailSequence) detailLog.value = data
  } catch (error) { if (sequence === detailSequence) store.notify(error.message, 'error') }
  finally { if (sequence === detailSequence) detailLoading.value = false }
}
function closeDetail() {
  const query = { ...route.query }
  delete query.logId
  router.push({ query })
}
watch(() => [route.query.page, route.query.size, ...Object.keys(filters).map(key => route.query[key])], load, { immediate: true })
watch(() => route.query.logId, loadDetail, { immediate: true })
onBeforeUnmount(() => { detailSequence++ })
</script>

<template>
  <div class="resource-page">
    <header class="topbar"><h1>AI 调用记录</h1><el-button :icon="Refresh" :loading="store.loading" @click="load">刷新</el-button></header>
    <section class="stats">
      <article><span>筛选总数</span><strong>{{ store.page.total }}</strong></article>
      <article><span>成功</span><strong>{{ store.page.summary?.success || 0 }}</strong></article>
      <article><span>调用失败</span><strong>{{ store.page.summary?.failed || 0 }}</strong></article>
      <article><span>配额拒绝</span><strong>{{ store.page.summary?.rejected || 0 }}</strong></article>
    </section>
    <form class="table-toolbar" @submit.prevent="search()">
      <el-input v-model="filters.userId" placeholder="用户 ID" aria-label="用户 ID" class="filter-id" />
      <el-select v-model="filters.featureType" placeholder="全部功能" clearable aria-label="功能" class="filter-select"><el-option v-for="value in features" :key="value" :value="value" :label="dataLabels[value]" /></el-select>
      <el-select v-model="filters.status" placeholder="全部状态" clearable aria-label="状态" class="filter-select"><el-option v-for="value in ['success', 'failed', 'rejected']" :key="value" :value="value" :label="dataLabels[value]" /></el-select>
      <input type="date" v-model="filters.dateFrom" aria-label="开始日期" />
      <input type="date" v-model="filters.dateTo" aria-label="结束日期" />
      <el-button :icon="Search" type="primary" native-type="submit">查询</el-button>
      <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
    </form>
    <el-table :data="store.page.list" v-loading="store.loading" empty-text="暂无调用记录">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="用户" width="100"><template #default="{ row }"><el-button link type="primary" @click="router.push({ path: '/users', query: { userId: row.userId } })">#{{ row.userId }}</el-button></template></el-table-column>
      <el-table-column label="功能" min-width="120"><template #default="{ row }">{{ dataLabels[row.featureType] || row.featureType }}</template></el-table-column>
      <el-table-column label="状态" min-width="110"><template #default="{ row }"><el-tag :type="row.status === 'success' ? 'success' : row.status === 'rejected' ? 'warning' : 'danger'">{{ dataLabels[row.status] }}</el-tag></template></el-table-column>
      <el-table-column prop="modelName" label="模型" min-width="140" show-overflow-tooltip />
      <el-table-column label="Token" min-width="110"><template #default="{ row }">{{ row.inputTokens == null ? '--' : Number(row.inputTokens) + Number(row.outputTokens || 0) }}</template></el-table-column>
      <el-table-column prop="estimatedCost" label="估算 CNY" min-width="120" />
      <el-table-column prop="createdAt" label="调用时间 (UTC)" min-width="200" />
      <el-table-column label="详情" width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="router.push({ query: { ...route.query, logId: row.id } })">查看</el-button></template></el-table-column>
    </el-table>
    <el-pagination :current-page="store.page.page" :page-size="store.page.size" :total="store.page.total" :page-sizes="[10, 20, 50]" layout="total, prev, pager, next, sizes"
      @current-change="page => search(page)" @size-change="size => search(1, size)" />
    <el-dialog :model-value="showDetail" title="AI 调用详情" width="min(800px, 94vw)" @close="closeDetail">
      <el-skeleton v-if="detailLoading" :rows="5" animated />
      <template v-else-if="detailLog">
        <el-descriptions :column="1" border><el-descriptions-item v-for="[key, value] in detailFields" :key="key" :label="columnLabels[key] || key">{{ dataLabels[value] || (value ?? '--') }}</el-descriptions-item></el-descriptions>
        <h3>输入内容</h3><pre class="log-content">{{ detailLog.inputText || '未记录' }}</pre>
        <h3>输出内容</h3><pre class="log-content">{{ detailLog.outputText || '未记录' }}</pre>
        <h3>错误信息</h3><pre class="log-content">{{ detailLog.errorMessage || '无' }}</pre>
      </template>
    </el-dialog>
  </div>
</template>
