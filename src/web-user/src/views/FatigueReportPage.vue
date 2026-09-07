<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, Activity, Check, Gauge, Layers, RotateCcw, TrendingUp, Users } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import type { FatigueReportTrendItem } from '../types'

const store = useAppStore()
const router = useRouter()

const period = ref<'week' | 'month'>('week')
const loading = ref(true)
const chartPlotH = 150
const chartTop = 16
const chartBottom = 30

const report = computed(() => store.fatigueReport)
const summary = computed(() => report.value?.summary ?? null)
const trend = computed<FatigueReportTrendItem[]>(() => report.value?.trend ?? [])

const slotWidth = computed(() => (period.value === 'week' ? 68 : 46))
const chartWidth = computed(() => Math.max(320, trend.value.length * slotWidth.value))

const maxLoad = computed(() => {
  let max = 1
  for (const item of trend.value) {
    max = Math.max(max, item.plannedLoad, item.completedLoad, item.teamCompletedLoad, item.totalCompletedLoad)
  }
  return max
})

const isEmpty = computed(() => {
  const s = summary.value
  if (!s) return true
  if (s.personalScheduleCount > 0) return false
  if (Number(s.teamCompletedLoad) > 0) return false
  if (s.surveyScoreAvg != null) return false
  return trend.value.every(item => item.surveyScore == null && item.predictedScore === 0)
})

function colCenter(index: number) {
  return index * slotWidth.value + slotWidth.value / 2
}
function loadY(value: number) {
  return chartTop + chartPlotH - (value / maxLoad.value) * chartPlotH
}
function scoreY(value: number) {
  return chartTop + chartPlotH - (Math.max(0, Math.min(100, value)) / 100) * chartPlotH
}
function loadHeight(value: number) {
  return (value / maxLoad.value) * chartPlotH
}
function dayLabel(iso: string) {
  return iso.slice(5)
}
function fmtNum(value: number | null | undefined): string {
  if (value == null) return '—'
  const n = Number(value)
  return Number.isInteger(n) ? String(n) : n.toFixed(1).replace(/\.0$/, '')
}
function fmtPercent(value: number | null | undefined): string {
  if (value == null) return '—'
  const pct = Number(value) * 100
  return (Number.isInteger(pct) ? String(pct) : pct.toFixed(1).replace(/\.0$/, '')) + '%'
}
function isPeak(iso: string) {
  return summary.value?.peakDay === iso
}

async function load() {
  loading.value = true
  try {
    await store.loadFatigueReport(period.value)
  } catch (error: any) {
    store.notify(error?.message || '加载疲劳回顾失败')
  } finally {
    loading.value = false
  }
}

async function switchPeriod(next: 'week' | 'month') {
  if (period.value === next) return
  period.value = next
  await load()
}

onMounted(load)
</script>

<template>
  <section class="fatigue-page">
    <div v-if="loading" class="detail-panel fatigue-loading">
      <RotateCcw class="spinning" :size="20" />
      <span>正在加载疲劳回顾...</span>
    </div>
    <template v-else-if="report">
      <header class="fatigue-page-head">
        <div>
          <p class="eyebrow">{{ report.dateFrom }} ~ {{ report.dateTo }} · {{ report.timezone }}</p>
          <h2>疲劳回顾报告</h2>
          <p class="hint">个人计划、个人完成与团队完成负荷分开统计；团队完成仅供展示，不参与模型与预测。</p>
        </div>
        <div class="report-head-actions">
          <div class="report-period-switch" role="tablist" aria-label="报告周期">
            <button role="tab" :aria-selected="period === 'week'" :class="{ active: period === 'week' }" @click="switchPeriod('week')">周</button>
            <button role="tab" :aria-selected="period === 'month'" :class="{ active: period === 'month' }" @click="switchPeriod('month')">月</button>
          </div>
          <button class="plain-button" @click="router.back()"><ArrowLeft :size="16" />返回</button>
        </div>
      </header>

      <div class="report-summary-grid">
        <article class="report-summary-card">
          <span class="report-summary-icon planned"><Gauge :size="18" /></span>
          <div><small>个人计划负荷</small><strong>{{ fmtNum(summary?.personalPlannedLoad) }}</strong><em>{{ summary?.personalScheduleCount ?? 0 }} 项日程</em></div>
        </article>
        <article class="report-summary-card">
          <span class="report-summary-icon completed"><Check :size="18" /></span>
          <div><small>个人完成负荷</small><strong>{{ fmtNum(summary?.personalCompletedLoad) }}</strong><em>{{ summary?.personalCompletedCount ?? 0 }} 项完成</em></div>
        </article>
        <article class="report-summary-card">
          <span class="report-summary-icon team"><Users :size="18" /></span>
          <div><small>团队完成负荷</small><strong>{{ fmtNum(summary?.teamCompletedLoad) }}</strong><em>仅供完成日展示</em></div>
        </article>
        <article class="report-summary-card">
          <span class="report-summary-icon total"><Layers :size="18" /></span>
          <div><small>总完成负荷</small><strong>{{ fmtNum(summary?.totalCompletedLoad) }}</strong><em>个人 + 团队</em></div>
        </article>
        <article class="report-summary-card">
          <span class="report-summary-icon peak"><TrendingUp :size="18" /></span>
          <div><small>峰值日</small><strong>{{ summary?.peakDay ? summary.peakDay.slice(5) : '—' }}</strong><em>负荷 {{ fmtNum(summary?.peakDayLoad) }}</em></div>
        </article>
        <article class="report-summary-card">
          <span class="report-summary-icon rate"><Activity :size="18" /></span>
          <div><small>个人完成率</small><strong>{{ fmtPercent(summary?.personalCompletionRate) }}</strong><em>仅个人日程口径</em></div>
        </article>
      </div>

      <div v-if="isEmpty" class="detail-panel report-empty">
        <TrendingUp :size="26" />
        <h3>这个周期还没有可回顾的数据</h3>
        <p>完成个人日程并填写日终调查后，这里会呈现你的疲劳趋势与负荷分布。</p>
      </div>

      <template v-else>
        <article class="detail-panel report-chart-card">
          <div class="section-title">
            <span class="section-icon fatigue"><Gauge :size="18" /></span>
            <div><h2>负荷柱状</h2><p>计划负荷 · 个人完成 · 团队完成（按日）</p></div>
          </div>
          <div class="report-legend">
            <span><i class="dot planned"></i>计划负荷</span>
            <span><i class="dot completed"></i>个人完成</span>
            <span><i class="dot team"></i>团队完成</span>
            <span class="muted">峰值日已高亮</span>
          </div>
          <div class="report-chart-scroll">
            <svg :viewBox="`0 0 ${chartWidth} ${chartTop + chartPlotH + chartBottom}`" :style="{ width: `${chartWidth}px` }" role="img" aria-label="负荷柱状图">
              <g v-for="(item, i) in trend" :key="item.localDate">
                <text v-if="isPeak(item.localDate)" :x="colCenter(i)" :y="chartTop - 4" class="report-peak-tag" text-anchor="middle">峰值</text>
                <line v-if="isPeak(item.localDate)" :x1="colCenter(i)" :y1="chartTop" :x2="colCenter(i)" :y2="chartTop + chartPlotH" class="report-peak-line" />
                <rect :x="colCenter(i) - slotWidth * 0.34" :y="loadY(item.plannedLoad)" :width="slotWidth * 0.2" :height="loadHeight(item.plannedLoad)" class="report-bar planned" rx="2" />
                <rect :x="colCenter(i) - slotWidth * 0.1" :y="loadY(item.completedLoad)" :width="slotWidth * 0.2" :height="loadHeight(item.completedLoad)" class="report-bar completed" rx="2" />
                <rect :x="colCenter(i) + slotWidth * 0.14" :y="loadY(item.teamCompletedLoad)" :width="slotWidth * 0.2" :height="loadHeight(item.teamCompletedLoad)" class="report-bar team" rx="2" />
                <text :x="colCenter(i)" :y="chartTop + chartPlotH + 16" class="report-axis-label" :class="{ peak: isPeak(item.localDate) }" text-anchor="middle">{{ dayLabel(item.localDate) }}</text>
              </g>
            </svg>
          </div>
        </article>

        <article class="detail-panel report-chart-card">
          <div class="section-title">
            <span class="section-icon time"><TrendingUp :size="18" /></span>
            <div><h2>疲劳趋势</h2><p>预计疲劳分（折线）与实际调查分（圆点）</p></div>
          </div>
          <div class="report-trend-stats">
            <span>平均预计 <strong>{{ fmtNum(summary?.predictedScoreAvg) }}</strong></span>
            <span>平均调查 <strong>{{ fmtNum(summary?.surveyScoreAvg) }}</strong></span>
          </div>
          <div class="report-chart-scroll">
            <svg :viewBox="`0 0 ${chartWidth} ${chartTop + chartPlotH + chartBottom}`" :style="{ width: `${chartWidth}px` }" role="img" aria-label="疲劳趋势折线图">
              <line :x1="0" :y1="scoreY(100)" :x2="chartWidth" :y2="scoreY(100)" class="report-score-cap" />
              <polyline :points="trend.map((item, i) => `${colCenter(i)},${scoreY(item.predictedScore)}`).join(' ')" class="report-score-line" />
              <g v-for="(item, i) in trend" :key="item.localDate">
                <circle v-if="item.surveyScore != null" :cx="colCenter(i)" :cy="scoreY(item.surveyScore)" r="3.4" class="report-score-dot" />
                <text :x="colCenter(i)" :y="chartTop + chartPlotH + 16" class="report-axis-label" text-anchor="middle">{{ dayLabel(item.localDate) }}</text>
              </g>
            </svg>
          </div>
        </article>

        <article class="detail-panel report-daily-card">
          <div class="section-title">
            <span class="section-icon fatigue"><Activity :size="18" /></span>
            <div><h2>逐日明细</h2><p>与日终调查原始数据一致</p></div>
          </div>
          <div class="report-chart-scroll">
            <div class="report-daily-list">
              <div class="report-daily-head">
                <span>日期</span><span>计划</span><span>个人完成</span><span>团队完成</span><span>总完成</span><span>预计分</span><span>调查分</span>
              </div>
              <div v-for="item in trend" :key="item.localDate" class="report-daily-row" :class="{ peak: isPeak(item.localDate) }">
                <span class="report-daily-date">{{ dayLabel(item.localDate) }}<em v-if="isPeak(item.localDate)">峰值</em></span>
                <span>{{ fmtNum(item.plannedLoad) }}</span>
                <span>{{ fmtNum(item.completedLoad) }}</span>
                <span>{{ fmtNum(item.teamCompletedLoad) }}</span>
                <span>{{ fmtNum(item.totalCompletedLoad) }}</span>
                <span>{{ item.predictedScore }}</span>
                <span>{{ item.surveyScore ?? '—' }}</span>
              </div>
            </div>
          </div>
        </article>
      </template>

      <p class="fatigue-disclaimer">{{ report.disclaimer }}</p>
    </template>
  </section>
</template>