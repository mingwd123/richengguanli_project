<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Download } from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'
import { datasetLabels, downloadCsv, queryPage } from '../utils/analytics'
import RecordTable from './RecordTable.vue'

const props = defineProps({ range: { type: Array, required: true } })
const route = useRoute()
const router = useRouter()
const store = useAdminStore()
const data = ref(null)
const error = ref('')
const loading = ref(false)
const exporting = ref(false)
const metric = computed(() => String(route.query.detailMetric || ''))
const visible = computed(() => Boolean(datasetLabels[metric.value]))
const page = computed(() => queryPage(route.query.detailPage))
let sequence = 0
let controller
function query() {
  return new URLSearchParams({ dateFrom: props.range[0], dateTo: props.range[1], selector: String(route.query.selector || ''),
    series: String(route.query.series || ''), page: String(page.value), size: '20' })
}
async function load() {
  const current = ++sequence
  controller?.abort()
  data.value = null
  error.value = ''
  if (!visible.value) return
  controller = new AbortController()
  loading.value = true
  try {
    const result = await store.request(`/admin/views/${metric.value}/details?${query()}`, { signal: controller.signal })
    if (sequence === current) data.value = result
  } catch (reason) { if (sequence === current && reason.name !== 'AbortError') error.value = reason.message }
  finally { if (sequence === current) loading.value = false }
}
function close() {
  const query = { ...route.query }
  for (const key of ['detailMetric', 'selector', 'series', 'detailPage']) delete query[key]
  router.push({ query })
}
async function exportDetails() {
  exporting.value = true
  try {
    const result = await store.request(`/admin/views/${metric.value}/export?${query()}`)
    downloadCsv(`${datasetLabels[metric.value]}-明细`, result.items, { dataset: datasetLabels[metric.value],
      dateFrom: props.range[0], dateTo: props.range[1], timezone: result.timezone, exportedAt: result.exportedAt,
      selector: result.selector, series: result.series, exportScope: '完整筛选结果' })
  } catch (reason) { store.notify(reason.message, 'error') }
  finally { exporting.value = false }
}
watch(() => [metric.value, route.query.selector, route.query.series, page.value, ...props.range], load, { immediate: true })
onBeforeUnmount(() => { sequence++; controller?.abort() })
</script>

<template>
  <el-drawer :model-value="visible" :title="`${datasetLabels[metric] || ''} · 明细`" size="min(100%, 1000px)" @close="close">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="analytics-section-heading">
      <span>{{ route.query.selector }} · {{ data?.total ?? '--' }} 条</span>
      <el-button :icon="Download" :loading="exporting" :disabled="!data" @click="exportDetails">导出全部明细</el-button>
    </div>
    <RecordTable :rows="data?.items || []" :loading="loading" />
    <el-pagination :current-page="page" :page-size="20" :total="data?.total || 0" layout="prev, pager, next"
      @current-change="value => router.push({ query: { ...route.query, detailPage: value } })" />
  </el-drawer>
</template>
