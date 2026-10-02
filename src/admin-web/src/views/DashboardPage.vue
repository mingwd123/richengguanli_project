<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  ArrowRight,
  Bell,
  Calendar,
  Document,
  List,
  Management,
  Monitor,
  Odometer,
  Timer,
  TrendCharts,
  User,
  UserFilled,
} from '@element-plus/icons-vue'
import { useAdminStore } from '../stores/admin'
import AnalyticsChart from '../components/AnalyticsChart.vue'
import { presetRange } from '../utils/analytics'

const store = useAdminStore()
const systemStatus = ref(null)
const trends = ref(null)
const trendError = ref('')
const range = presetRange(7)
const trendQuery = new URLSearchParams({ dateFrom: range[0], dateTo: range[1] })
const growthRows = computed(() => (trends.value?.[0]?.items || []).map(row => ({
  ...row, dau: trends.value?.[1]?.items?.find(item => item.date === row.date)?.dau ?? null,
})))
const healthStatus = computed(() => {
  if (!systemStatus.value) return { title: '正在检查', detail: '读取数据库与资源状态', tone: 'pending' }
  if (systemStatus.value.status === 'unknown') return { title: '状态未知', detail: '系统检查请求未成功', tone: 'warning' }
  if (systemStatus.value.status === 'down') return { title: '检查失败', detail: '数据库健康检查未通过', tone: 'danger' }
  if (systemStatus.value.status === 'warning') return { title: '需要关注', detail: `${systemStatus.value.alerts?.length || 0} 项运维提示`, tone: 'warning' }
  return { title: '服务正常', detail: '数据库与应用健康检查通过', tone: 'success' }
})
const quickLinks = computed(() => [
  { to: '/users', label: '用户管理', description: '账号状态与用户详情', icon: User, tone: 'teal' },
  { to: '/teams', label: '团队管理', description: '团队结构与成员信息', icon: UserFilled, tone: 'blue' },
  { to: '/schedules', label: '日程管理', description: '全站日程记录', icon: Calendar, tone: 'amber' },
  { to: '/team-tasks', label: '团队任务', description: '任务状态与执行人', icon: List, tone: 'coral' },
  { to: '/notifications', label: '通知记录', description: '站内通知投递记录', icon: Bell, tone: 'blue' },
  { to: '/reminders', label: '提醒记录', description: '定时提醒执行结果', icon: Timer, tone: 'amber' },
  ...(store.isSuperAdmin ? [{ to: '/admin-users', label: '管理员账号', description: '后台访问权限', icon: Management, tone: 'teal' }] : []),
  { to: '/operation-logs', label: '操作日志', description: '敏感操作审计', icon: Document, tone: 'coral' },
  { to: '/views/fatigue', label: '疲劳 / 负荷', description: '疲劳分布与高负荷预警', icon: Odometer, tone: 'coral' },
  { to: '/views/ops', label: '运营健康', description: '增长、活跃与留存趋势', icon: TrendCharts, tone: 'teal' },
  { to: '/views/system', label: '系统状态', description: '数据库、资源与备份检查', icon: Monitor, tone: 'blue' },
])
const metricItems = computed(() => [
  { label: '用户', value: store.dashboardStats.users, detail: `${store.dashboardStats.enabledUsers ?? '--'} 启用账号` },
  { label: '今日活跃用户', value: store.dashboardStats.dailyActiveUsers, detail: 'UTC 当日去重访问' },
  { label: '团队', value: store.dashboardStats.teams, detail: '协作空间' },
  { label: '待办日程', value: store.dashboardStats.pendingSchedules, detail: '全站待处理' },
  { label: '活跃任务', value: store.dashboardStats.activeTeamTasks, detail: '团队执行中' },
  { label: '待发提醒', value: store.dashboardStats.pendingReminders, detail: `${store.dashboardStats.unreadNotifications} 未读通知` },
  { label: '今日 AI', value: store.dashboardStats.aiCallsToday, detail: '调用次数' },
])
onMounted(async () => {
  await Promise.all([
    store.fetchDashboardStats(),
    store.request('/admin/ops/system-status')
      .then(value => { systemStatus.value = value })
      .catch(() => { systemStatus.value = { status: 'unknown', alerts: [] } }),
    Promise.all(['ops/growth', 'ops/retention', 'fatigue/distribution'].map(metric =>
      store.request(`/admin/views/${metric}?${trendQuery}`)))
      .then(value => { trends.value = value })
      .catch(error => { trendError.value = error.message || '趋势加载失败' }),
  ])
})
</script>

<template>
  <div class="dashboard">
    <header class="topbar">
      <div><h1>运营总览</h1><p>快速进入资源管理和系统审计。</p></div>
    </header>

    <section class="dashboard-attention">
      <router-link to="/views/system"><strong>{{ healthStatus.title }}</strong><span>{{ healthStatus.detail }}</span><el-icon><ArrowRight /></el-icon></router-link>
      <router-link to="/views/security"><strong>{{ store.dashboardStats.pendingRisks ?? '--' }} 条待复核风险</strong><span>安全风控</span><el-icon><ArrowRight /></el-icon></router-link>
      <ul v-if="systemStatus?.alerts?.length"><li v-for="alert in systemStatus.alerts.slice(0, 3)" :key="alert">{{ alert }}</li></ul>
    </section>

    <section class="dashboard-metrics">
      <article v-for="metric in metricItems" :key="metric.label"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong><small>{{ metric.detail }}</small></article>
    </section>

    <div class="dashboard-section-head"><div><h2>近 7 天趋势</h2></div><span>{{ range[0] }} ~ {{ range[1] }}</span></div>
    <el-alert v-if="trendError" :title="trendError" type="error" show-icon :closable="false" class="analytics-alert" />
    <el-skeleton v-else-if="!trends" :rows="5" animated />
    <div v-else class="analytics-chart-grid">
      <AnalyticsChart title="增长与活跃" :rows="growthRows" :series="[{ key: 'newUsers', name: '新增用户', type: 'bar' }, { key: 'dau', name: 'DAU' }]">
        <template #action><router-link to="/views/ops">运营健康</router-link></template>
      </AnalyticsChart>
      <AnalyticsChart title="疲劳分布" :rows="trends[2]?.items || []" category="name" type="bar">
        <template #action><router-link to="/views/fatigue">疲劳 / 负荷</router-link></template>
      </AnalyticsChart>
    </div>

    <div class="dashboard-section-head"><div><h2>管理入口</h2><p>按资源类型进入对应工作区</p></div><span>{{ quickLinks.length }} 个模块</span></div>
    <section class="quick-links">
      <router-link v-for="item in quickLinks" :key="item.to" :to="item.to" class="quick-link-card">
        <span :class="['quick-link-icon', item.tone]"><el-icon><component :is="item.icon" /></el-icon></span>
        <div><strong>{{ item.label }}</strong><span>{{ item.description }}</span></div>
        <el-icon class="quick-link-arrow"><ArrowRight /></el-icon>
      </router-link>
    </section>
  </div>
</template>
