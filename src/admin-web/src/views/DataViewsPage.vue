<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'
import AnalyticsChart from '../components/AnalyticsChart.vue'
import AnalyticsRange from '../components/AnalyticsRange.vue'
import AnalyticsDetails from '../components/AnalyticsDetails.vue'
import { dataLabels, datasetLabels, downloadCsv, formatMetric as fmt, presetRange, queryPage, validRange, viewCatalog, viewEndpoint } from '../utils/analytics'

const props = defineProps({ kind: { type: String, required: true } })
const store = useAdminStore()
const router = useRouter()
const route = useRoute()
const initialRange = validRange([route.query.dateFrom, route.query.dateTo])
  ? [route.query.dateFrom, route.query.dateTo]
  : presetRange(7)
const range = ref(initialRange)
const loading = ref(false)
const error = ref('')
const datasets = ref([])
const primaryIndex = computed(() => props.kind === 'fatigue' ? 2 : props.kind === 'ai' ? 3 : 0)
const page = computed(() => queryPage(route.query[`page${primaryIndex.value}`]))
const exporting = ref(false)
const reviewRow = ref(null)
const reviewStatus = ref('pending')
const reviewNote = ref('')
const reviewing = ref(false)
const changingUser = ref(null)
const config = computed(() => viewCatalog.find(view => view.key === props.kind))
const rows = index => datasets.value[index]?.items || []
const named = index => rows(index).map(row => ({ ...row, originalName: row.name, name: dataLabels[row.name] || row.name }))
const sum = (index, key) => rows(index).reduce((total, row) => total + Number(row[key] || 0), 0)
const latest = (index, key) => [...rows(index)].reverse().find(row => row[key] != null)?.[key]
const tableTotal = computed(() => datasets.value[primaryIndex.value]?.total || 0)
const tracking = computed(() => datasets.value.find(data => data?.trackingStartedAt)?.trackingStartedAt?.slice(0, 10))
let sequence = 0
let controller

const metrics = computed(() => {
  if (props.kind === 'fatigue') return [
    ['调查记录', fmt(sum(0, 'value')), '按用户 / 日期去重'],
    ['调查完成率', sum(1, 'expected') ? `${fmt(sum(1, 'completed') / sum(1, 'expected') * 100, 1)}%` : '--', '已填 / 应填'],
    ['高负荷记录', fmt(tableTotal.value), '个人负荷达到承受值 80%'],
    ['跳过调查', fmt(sum(1, 'skipped')), '仍计入应填分母'],
  ]
  if (props.kind === 'ops') return [
    ['新增用户', fmt(sum(0, 'newUsers')), '选定注册日期'],
    ['日活 DAU', fmt(latest(1, 'dau')), '最近有采集数据的一日'],
    ['周活 WAU', fmt(latest(1, 'wau')), '连续 7 日去重'],
    ['次日留存', latest(1, 'nextDayRate') == null ? '--' : `${fmt(latest(1, 'nextDayRate'), 1)}%`, '最近完整观察批次'],
  ]
  if (props.kind === 'collab') return [
    ['创建任务', fmt(rows(0)[0]?.value), '按任务计数'],
    ['已接受', fmt(rows(0)[1]?.value), '至少一位执行人接受'],
    ['已完成', fmt(rows(0)[2]?.value), '任务整体完成'],
    ['按时完成', fmt(sum(1, 'onTime')), '选定完成日期'],
  ]
  if (props.kind === 'ai') return [
    ['调用次数', fmt(sum(0, 'calls')), '含调用失败'],
    ['Token', rows(0).some(row => row.tokens != null && row.calls > 0) ? fmt(sum(0, 'tokens')) : '--', '供应商返回的用量'],
    ['已计价成本', sum(0, 'pricedCalls') ? `¥${fmt(sum(0, 'cost'), 6)}` : '--', `${fmt(sum(0, 'pricedCalls'))} 次调用已计价`],
    ['失败次数', fmt(sum(0, 'failures')), '最终请求失败'],
  ]
  return [
    ['异常登录记录', fmt(tableTotal.value), '失败或网段变化'],
    ['注册来源分组', fmt(datasets.value[1]?.total), '完整筛选范围'],
    ['来源内注册数', fmt(datasets.value[1]?.summary?.registrations), '已采集的自助注册'],
    ['批量注册来源', fmt(datasets.value[1]?.summary?.highRiskSources), '同网段单日不少于 10 次'],
  ]
})

async function load() {
  const current = ++sequence
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const result = await Promise.allSettled(config.value.endpoints.map((metric, index) => {
      const query = new URLSearchParams({ dateFrom: range.value[0], dateTo: range.value[1], page: String(queryPage(route.query[`page${index}`])), size: '20' })
      if (props.kind === 'security' && /^\d+$/.test(String(route.query.userId || ''))) query.set('userId', route.query.userId)
      return store.request(`${viewEndpoint(metric)}?${query}`, { signal: controller.signal })
    }))
    if (current === sequence) {
      datasets.value = result.map(item => item.status === 'fulfilled' ? item.value : null)
      error.value = result.flatMap((item, index) => item.status === 'rejected' && item.reason.name !== 'AbortError'
        ? [`${datasetLabels[config.value.endpoints[index]]}：${item.reason.message}`] : []).join('；')
    }
  } catch (reason) {
    if (current === sequence && reason.name !== 'AbortError') { datasets.value = []; error.value = reason.message || '数据加载失败' }
  } finally { if (current === sequence) loading.value = false }
}
function changeRange(value) {
  const query = { ...route.query, dateFrom: value[0], dateTo: value[1] }
  for (const key of ['page0', 'page1', 'page2', 'page3', 'detailMetric', 'selector', 'series', 'detailPage']) delete query[key]
  router.push({ query })
}
function changePage(value, index = primaryIndex.value) { router.push({ query: { ...route.query, [`page${index}`]: value } }) }
async function exportData({ index, all }) {
  if (exporting.value || !datasets.value[index]) return
  exporting.value = true
  const metric = config.value.endpoints[index]
  const selectedRange = [...range.value]
  try {
    const query = new URLSearchParams({ dateFrom: selectedRange[0], dateTo: selectedRange[1] })
    if (props.kind === 'security' && route.query.userId) query.set('userId', route.query.userId)
    const data = all ? await store.request(`/admin/views/${metric}/export?${query}`) : datasets.value[index]
    downloadCsv(`${datasetLabels[metric]}-${selectedRange.join('_')}`, data.items || [], {
      dataset: datasetLabels[metric], dateFrom: selectedRange[0], dateTo: selectedRange[1],
      timezone: data.timezone, exportedAt: data.exportedAt || new Date().toISOString(), exportScope: all ? '完整筛选结果' : '当前页',
    })
  } catch (reason) { store.notify(reason.message, 'error') }
  finally { exporting.value = false }
}
function userDetail(id) { if (id) router.push({ path: '/users', query: { userId: id } }) }
function drill(path, extra = {}) { router.push({ path, query: { dateFrom: range.value[0], dateTo: range.value[1], ...extra } }) }
function inspect(metric, row, series = 'value') {
  if (!row) return
  const selector = metric === 'ai/key-health' ? row.keyId ?? 'environment'
    : metric === 'security/register-analysis' ? row.ipSegment : row.originalName || row.date || row.name
  router.push({ query: { ...route.query, dateFrom: range.value[0], dateTo: range.value[1],
    detailMetric: metric, selector: String(selector), series: metric === 'security/register-analysis' ? row.date : series, detailPage: '1' } })
}
function openReview(row) { reviewRow.value = row; reviewStatus.value = row.reviewStatus; reviewNote.value = row.reviewNote || '' }
async function saveReview() {
  if (!reviewNote.value.trim() || reviewing.value) return
  reviewing.value = true
  try {
    await store.request(`/admin/security/events/${reviewRow.value.id}/review`, { method: 'PUT',
      body: JSON.stringify({ status: reviewStatus.value, note: reviewNote.value.trim(), revision: reviewRow.value.revision }) })
    reviewRow.value = null
    store.notify('复核已保存')
    await load()
  } catch (reason) {
    store.notify(reason.code === 409 ? '记录已被其他管理员复核，请刷新后重试' : reason.message, 'error')
    if (Number(reason.code) === 409) { reviewRow.value = null; await load() }
  } finally { reviewing.value = false }
}
async function toggleBan(row) {
  const ban = row.userStatus === 'active'
  let reason
  try { reason = (await ElMessageBox.prompt(ban ? '封禁后将撤销用户会话，请填写原因。' : '请填写解封原因。', ban ? '封禁账号' : '解封账号', {
    type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消', inputValidator: value => Boolean(value?.trim()) && value.trim().length <= 1000 || '请填写 1 至 1000 字的原因',
  })).value }
  catch { return }
  changingUser.value = row.userId
  try {
    await store.request(`/admin/users/${row.userId}/${ban ? 'ban' : 'unban'}`, { method: 'PUT', body: JSON.stringify({ reason }) })
    store.notify(ban ? '账号已封禁' : '账号已解封')
    await load()
  } catch (reason) { store.notify(reason.message, 'error') }
  finally { changingUser.value = null }
}
watch(() => [props.kind, route.query.userId, route.query.dateFrom, route.query.dateTo, ...[0, 1, 2, 3].map(index => route.query[`page${index}`])],
  () => { range.value = validRange([route.query.dateFrom, route.query.dateTo]) ? [route.query.dateFrom, route.query.dateTo] : presetRange(7); load() }, { immediate: true })
onBeforeUnmount(() => { sequence++; controller?.abort() })
</script>

<template>
  <div class="analytics-page">
    <header class="topbar">
      <div><span class="analytics-eyebrow">数据视图</span><h1>{{ config.label }}</h1></div>
      <el-button v-if="kind === 'ai' && store.isSuperAdmin" :icon="ArrowRight" @click="router.push('/ai-quota')">配额与模型配置</el-button>
    </header>
    <AnalyticsRange :model-value="range" :loading="loading" :unit="config.unit" :exportable="!!datasets.length" :datasets="config.endpoints" :exporting="exporting" @update:model-value="changeRange" @refresh="load" @export="exportData" />
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="analytics-alert" />
    <el-skeleton v-if="loading && !datasets.length" :rows="10" animated />
    <template v-else>
      <section class="analytics-metrics" :aria-busy="loading">
        <article v-for="metric in metrics" :key="metric[0]"><span>{{ metric[0] }}</span><strong>{{ metric[1] }}</strong><small>{{ metric[2] }}</small></article>
      </section>
      <div v-if="kind === 'fatigue'" class="analytics-chart-grid">
        <AnalyticsChart title="疲劳分布" :rows="rows(0)" category="name" type="bar" @select="(row, series) => inspect('fatigue/distribution', row, series)" note="用户本地日期 · 每次调查一条记录，包含 0 分与 100 分。" />
        <AnalyticsChart title="调查完成情况" :rows="rows(1)" :series="[{ key: 'expected', name: '应填' }, { key: 'completed', name: '已填' }, { key: 'rate', name: '完成率', axis: 1, unit: '%' }]"
          @select="(row, series) => inspect('fatigue/completion', row, series)" note="应填为个人完成负荷大于 0 且当前开启追踪及调查的用户日，并包含已提交调查；跳过仍计入分母。" />
      </div>
      <template v-if="kind === 'ops'">
        <p class="analytics-note">活跃采集始于 {{ tracking || '--' }}。采集前、未满观察期或无注册样本的留存显示为待观察。</p>
        <div class="analytics-chart-grid">
          <AnalyticsChart title="用户增长" :rows="rows(0)" :series="[{ key: 'newUsers', name: '新增用户', type: 'bar' }, { key: 'totalUsers', name: '累计用户', axis: 1 }]" @select="(row, series) => inspect('ops/growth', row, series)" />
          <AnalyticsChart title="活跃趋势" :rows="rows(1)" :series="[{ key: 'dau', name: 'DAU' }, { key: 'wau', name: 'WAU' }]" @select="(row, series) => inspect('ops/retention', row, series)" />
          <AnalyticsChart title="次日 / 7 日留存" :rows="rows(1)" :series="[{ key: 'nextDayRate', name: '次日留存', unit: '%' }, { key: 'day7Rate', name: '7 日留存', unit: '%' }]" @select="(row, series) => inspect('ops/retention', row, series)" note="按注册日分组；目标观察日完整结束后计算。空缺点为待观察，不等于 0%。" />
          <AnalyticsChart title="注册用户当前状态" :rows="named(2)" category="name" type="pie" @select="row => inspect('ops/status-distribution', row)" note="选定日期内注册用户的当前状态；禁用优先，三个分类互斥。" />
        </div>
        <div class="analytics-resource-links"><el-button :icon="ArrowRight" @click="drill('/users')">查看注册用户</el-button></div>
      </template>
      <template v-if="kind === 'collab'">
        <div class="analytics-chart-grid">
          <AnalyticsChart title="任务流转漏斗" :rows="named(0)" category="name" type="funnel" @select="row => inspect('collab/funnel', row)" note="选定日期创建的任务当前流转情况；多人任务只计一次。" />
          <AnalyticsChart title="完成与取消趋势" :rows="rows(1)" :series="[{ key: 'onTime', name: '按时完成' }, { key: 'overdue', name: '逾期完成' }, { key: 'cancelled', name: '取消' }]" @select="(row, series) => inspect('collab/completion-trend', row, series)" note="按完成 / 取消日期统计；完成时间取有效执行人的最晚完成时间。" />
          <AnalyticsChart title="完成率趋势" :rows="rows(1)" :series="[{ key: 'rate', name: '完成占结案比例', unit: '%' }, { key: 'onTimeRate', name: '按时完成比例', unit: '%' }]" @select="(row, series) => inspect('collab/completion-trend', row, series)" />
          <AnalyticsChart title="团队人数分布" :rows="rows(2)" category="name" type="bar" @select="row => inspect('collab/team-distribution', row)" note="选定日期创建的团队，按当前有效成员数分组。" />
        </div>
        <div class="analytics-resource-links"><el-button :icon="ArrowRight" @click="drill('/team-tasks')">查看任务</el-button><el-button :icon="ArrowRight" @click="drill('/teams')">查看团队</el-button></div>
      </template>
      <template v-if="kind === 'ai'">
        <div class="analytics-chart-grid">
          <AnalyticsChart title="调用与 Token 趋势" :rows="rows(0)" :series="[{ key: 'calls', name: '调用次数' }, { key: 'tokens', name: 'Token', axis: 1 }]" @select="(row, series) => inspect('ai/cost-trend', row, series)" />
          <AnalyticsChart title="估算成本" :rows="rows(0)" :series="[{ key: 'cost', name: '成本', unit: 'CNY' }]" @select="(row, series) => inspect('ai/cost-trend', row, series)" note="按调用时模型单价估算；未配置单价或供应商未返回用量的调用不计价，历史成本不会被新单价重算。" />
          <AnalyticsChart title="功能使用分布" :rows="named(1)" category="name" type="pie" @select="row => inspect('ai/feature-distribution', row)" />
          <section class="analytics-table-section">
            <div class="analytics-section-heading"><h2>Key 池健康</h2><el-button text :icon="ArrowRight" @click="drill('/ai-logs')">调用明细</el-button></div>
            <el-table :data="rows(2)" empty-text="暂无真实调用记录">
              <el-table-column prop="name" label="Key" min-width="120" show-overflow-tooltip />
              <el-table-column prop="calls" label="尝试次数" width="95" />
              <el-table-column label="失败率" width="90"><template #default="{ row }">{{ fmt(row.failureRate, 1) }}%</template></el-table-column>
              <el-table-column prop="switches" label="切入次数" width="95" />
              <el-table-column label="明细" width="70"><template #default="{ row }"><el-button link type="primary" @click="inspect('ai/key-health', row)">查看</el-button></template></el-table-column>
            </el-table>
            <p class="analytics-note">包含重试与失败尝试，不含管理员连接测试；不展示密钥内容。</p>
          </section>
        </div>
        <section class="analytics-table-section">
          <div class="analytics-section-heading"><h2>配额告警</h2><span>{{ datasets[3]?.total || 0 }} 条</span></div>
          <el-table :data="rows(3)" v-loading="loading" empty-text="所选日期内没有配额超限记录">
            <el-table-column label="用户" min-width="100"><template #default="{ row }"><el-button link type="primary" @click="userDetail(row.userId)">#{{ row.userId }}</el-button></template></el-table-column>
            <el-table-column label="额度范围" min-width="110"><template #default="{ row }">{{ dataLabels[row.scopeType] || row.scopeType }}<template v-if="row.scopeType !== 'global'"> #{{ row.subjectId }}</template></template></el-table-column>
            <el-table-column prop="dailyLimit" label="每日上限" min-width="100" />
            <el-table-column prop="usedCalls" label="已用" min-width="90" />
            <el-table-column label="类型" min-width="100"><template #default="{ row }">{{ dataLabels[row.alertKind] || row.alertKind }}</template></el-table-column>
            <el-table-column prop="createdAt" label="告警时间 (UTC)" min-width="185" />
          </el-table>
          <el-pagination :current-page="page" :page-size="20" :total="datasets[3]?.total || 0" layout="prev, pager, next" @current-change="changePage" />
        </section>
      </template>
      <section v-if="kind === 'fatigue'" class="analytics-table-section">
        <div class="analytics-section-heading"><h2>高负荷用户</h2><span>{{ tableTotal }} 条用户日记录</span></div>
        <el-table :data="rows(2)" v-loading="loading" empty-text="所选日期内没有高负荷记录">
          <el-table-column label="用户" min-width="130"><template #default="{ row }"><el-button link type="primary" @click="userDetail(row.userId)">{{ row.nickname }} #{{ row.userId }}</el-button></template></el-table-column>
          <el-table-column prop="date" label="本地日期" width="115" />
          <el-table-column prop="plannedLoad" label="计划负荷" width="100" />
          <el-table-column prop="completedLoad" label="完成负荷" width="100" />
          <el-table-column prop="capacity75" label="承受值" width="100" />
          <el-table-column label="负荷占比" width="110"><template #default="{ row }"><el-tag :type="row.riskLevel === 'high' ? 'danger' : 'warning'">{{ row.loadPercent }}%</el-tag></template></el-table-column>
          <el-table-column prop="snapshotAt" label="快照更新时间" min-width="185" />
        </el-table>
        <p class="analytics-note">只读个人负荷快照，计划与完成取较大值；承受值为当前 capacity75，不叠加团队负荷。80% 起关注，100% 起高负荷。</p>
        <el-pagination :current-page="page" :page-size="20" :total="tableTotal" layout="prev, pager, next" @current-change="changePage" />
      </section>
      <template v-if="kind === 'security'">
        <el-alert title="网段变化仅为风险信号，不代表异地登录。注册来源从采集上线后开始记录，不含管理员创建账号。" type="info" :closable="false" show-icon class="analytics-alert" />
        <section class="analytics-table-section">
          <div class="analytics-section-heading"><h2>异常登录</h2><el-button v-if="route.query.userId" text @click="router.replace('/views/security')">清除用户筛选</el-button></div>
          <el-table :data="rows(0)" v-loading="loading" empty-text="暂无异常登录记录">
            <el-table-column label="用户" min-width="135"><template #default="{ row }"><el-button v-if="row.userId" link type="primary" @click="userDetail(row.userId)">{{ row.nickname || '账号' }} #{{ row.userId }}</el-button><span v-else>未识别账号</span></template></el-table-column>
            <el-table-column prop="ipAddress" label="来源 IP" min-width="140" />
            <el-table-column label="风险" width="100"><template #default="{ row }"><el-tag :type="row.riskLevel === 'high' ? 'danger' : 'warning'">{{ dataLabels[row.riskLevel] }}</el-tag></template></el-table-column>
            <el-table-column label="原因" min-width="180"><template #default="{ row }">{{ dataLabels[row.reason] || row.reason }}</template></el-table-column>
            <el-table-column prop="createdAt" label="时间 (UTC)" min-width="185" />
            <el-table-column label="复核" min-width="110"><template #default="{ row }"><el-button link type="primary" @click="openReview(row)">{{ dataLabels[row.reviewStatus] || '待处理' }}</el-button></template></el-table-column>
            <el-table-column prop="reviewNote" label="备注" min-width="140" show-overflow-tooltip />
            <el-table-column prop="reviewedBy" label="复核人 ID" width="100" />
            <el-table-column label="账号操作" width="100"><template #default="{ row }"><el-button v-if="row.userId && row.userStatus" link :type="row.userStatus === 'active' ? 'danger' : 'primary'" :disabled="changingUser !== null" @click="toggleBan(row)">{{ row.userStatus === 'active' ? '封禁' : '解封' }}</el-button></template></el-table-column>
          </el-table>
          <el-pagination :current-page="page" :page-size="20" :total="tableTotal" layout="prev, pager, next" @current-change="changePage" />
        </section>
        <section class="analytics-table-section">
          <div class="analytics-section-heading"><h2>注册来源</h2><span>IPv4 /24 · IPv6 /48</span></div>
          <el-table :data="rows(1)" empty-text="暂无已采集的注册来源">
            <el-table-column prop="date" label="注册日期" min-width="120" />
            <el-table-column prop="ipSegment" label="IP 网段" min-width="180" />
            <el-table-column prop="registrations" label="注册数" width="100" />
            <el-table-column label="风险" width="100"><template #default="{ row }"><el-tag :type="row.riskLevel === 'high' ? 'danger' : row.riskLevel === 'medium' ? 'warning' : 'info'">{{ dataLabels[row.riskLevel] }}</el-tag></template></el-table-column>
            <el-table-column label="明细" width="80"><template #default="{ row }"><el-button link type="primary" @click="inspect('security/register-analysis', row)">查看</el-button></template></el-table-column>
          </el-table>
          <el-pagination :current-page="queryPage(route.query.page1)" :page-size="20" :total="datasets[1]?.total || 0" layout="prev, pager, next" @current-change="value => changePage(value, 1)" />
        </section>
      </template>
    </template>
    <AnalyticsDetails :range="range" />
    <el-dialog :model-value="!!reviewRow" title="风险复核" width="min(560px, 94vw)" :close-on-click-modal="!reviewing" :show-close="!reviewing" @close="reviewRow = null">
      <el-form label-position="top">
        <el-form-item label="状态"><el-select v-model="reviewStatus" :disabled="reviewing"><el-option v-for="value in ['pending', 'confirmed', 'false_positive', 'resolved']" :key="value" :value="value" :label="dataLabels[value]" /></el-select></el-form-item>
        <el-form-item label="复核备注"><el-input v-model="reviewNote" type="textarea" :rows="4" maxlength="1000" show-word-limit :disabled="reviewing" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="reviewing" @click="reviewRow = null">取消</el-button><el-button type="primary" :loading="reviewing" :disabled="!reviewNote.trim()" @click="saveReview">保存复核</el-button></template>
    </el-dialog>
  </div>
</template>
