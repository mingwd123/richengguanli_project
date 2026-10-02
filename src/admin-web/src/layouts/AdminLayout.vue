<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell, Calendar, CircleCheck, Connection, DataAnalysis, Document, Expand,
  Fold, House, List, Management, Moon, Sunny, Tickets, Timer, User, UserFilled,
  TrendCharts, Warning, Monitor, Odometer,
} from '@element-plus/icons-vue'
import { useAdminStore } from '@/stores/admin'

const store = useAdminStore()
const router = useRouter()
const route = useRoute()
const compactMedia = window.matchMedia('(max-width: 900px)')
const isCollapse = ref(compactMedia.matches)
const runtimeStatus = ref(null)
let runtimeTimer
const allMenuItems = [
  { path: '/', label: '仪表盘', icon: House, group: '资源管理' },
  { path: '/users', label: '用户管理', icon: User, group: '资源管理' },
  { path: '/teams', label: '团队管理', icon: UserFilled, group: '资源管理' },
  { path: '/schedules', label: '日程管理', icon: Calendar, group: '资源管理' },
  { path: '/team-tasks', label: '团队任务', icon: List, group: '资源管理' },
  { path: '/notifications', label: '通知记录', icon: Bell, group: '资源管理' },
  { path: '/tickets', label: '工单管理', icon: Tickets, group: '资源管理' },
  { path: '/reminders', label: '提醒记录', icon: Timer, group: '资源管理' },
  { path: '/admin-users', label: '管理员', icon: Management, group: '系统设置' },
  { path: '/operation-logs', label: '操作日志', icon: Document, group: '系统设置' },
  { path: '/ai-config', label: 'AI 配置', icon: Connection, group: '系统设置' },
  { path: '/ai-logs', label: 'AI 调用记录', icon: DataAnalysis, group: '系统设置' },
  { path: '/views/fatigue', label: '疲劳 / 负荷', icon: Odometer, group: '数据视图' },
  { path: '/views/ops', label: '运营健康', icon: TrendCharts, group: '数据视图' },
  { path: '/views/collab', label: '协作任务', icon: List, group: '数据视图' },
  { path: '/views/ai', label: 'AI 成本', icon: DataAnalysis, group: '数据视图' },
  { path: '/views/system', label: '系统状态', icon: Monitor, group: '数据视图' },
  { path: '/views/security', label: '安全风控', icon: Warning, group: '数据视图' },
  { path: '/ai-quota', label: 'AI 配额', icon: Connection, group: '系统设置' },
]
const superAdminOnlyPaths = new Set(['/admin-users', '/ai-config', '/ai-quota'])
const menuItems = computed(() => allMenuItems.filter(item => !superAdminOnlyPaths.has(item.path) || store.isSuperAdmin))
const menuGroups = computed(() => menuItems.value.reduce((groups, item) => {
  const group = item.group || '资源管理'
  ;(groups[group] ||= []).push(item)
  return groups
}, {}))
const routeMeta = computed(() => {
  const descriptions = {
    '/': '查看系统资源和管理入口',
    '/users': '检索用户并管理账号状态',
    '/teams': '查看团队、成员和运行状态',
    '/schedules': '审查全站个人日程记录',
    '/team-tasks': '跟踪团队任务和执行状态',
    '/notifications': '查看站内通知发送记录',
    '/reminders': '检查提醒任务与投递状态',
    '/tickets': '处理公开问题反馈与模块开关',
    '/admin-users': '维护后台管理员账号',
    '/operation-logs': '追踪后台敏感操作记录',
    '/ai-config': '管理 AI 服务提供商配置',
    '/ai-logs': '审查 AI 功能调用明细',
    '/views/fatigue': '查看全站疲劳分布、调查完成和高负荷预警',
    '/views/ops': '观察用户增长、活跃和留存',
    '/views/collab': '分析任务流转、完成和团队规模',
    '/views/ai': '观察 AI 调用、成本和 Key 池健康',
    '/views/system': '查看数据库、资源和备份状态',
    '/views/security': '识别异常登录与批量注册来源',
    '/ai-quota': '配置 AI 调用额度与成本单价',
  }
  return {
    title: menuItems.value.find(item => item.path === route.path)?.label || '管理后台',
    description: descriptions[route.path] || 'Dayliane 管理控制台',
  }
})
const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: 'long', day: 'numeric', weekday: 'long'
}).format(new Date()))

function handleSelect(path) { router.push(path) }
function handleLogout() { store.logout(); router.replace('/login') }
function handleMediaChange(event) { if (event.matches) isCollapse.value = true }
async function loadRuntimeStatus() {
  try { runtimeStatus.value = await store.request('/admin/ops/system-status') } catch { runtimeStatus.value = { status: 'unknown' } }
}
const runtimeEnvironment = computed(() => runtimeStatus.value?.environment || '环境未标注')
const runtimeTone = computed(() => runtimeStatus.value?.status || 'unknown')
const runtimeLabel = computed(() => runtimeTone.value === 'up' ? '运行正常' : runtimeTone.value === 'warning' ? '需要关注' : runtimeTone.value === 'down' ? '服务异常' : '状态未知')
watch(() => route.path, () => store.invalidateRequests(), { flush: 'sync' })

watch(() => store.token, value => {
  if (!value && route.path !== '/login') router.replace('/login')
})

onMounted(async () => {
  compactMedia.addEventListener('change', handleMediaChange)
  if (store.token && !store.profile) {
    try { await store.loadProfile() } catch { return }
  }
  if (route.meta.superAdmin && !store.isSuperAdmin) await router.replace('/')
  await loadRuntimeStatus()
  runtimeTimer = setInterval(loadRuntimeStatus, 60000)
})
onUnmounted(() => {
  compactMedia.removeEventListener('change', handleMediaChange)
  clearInterval(runtimeTimer)
})
</script>

<template>
  <el-container v-if="store.token" class="pure-admin-layout">
    <el-aside :width="isCollapse ? '76px' : '248px'" class="sidebar">
      <div class="sidebar-logo" :class="{ collapsed: isCollapse }">
        <span class="logo-mark">D</span>
        <span v-show="!isCollapse" class="admin-brand-copy"><strong>Dayliane</strong><small>Admin console</small></span>
      </div>
      <p v-show="!isCollapse" class="sidebar-caption">管理工作台</p>
      <el-scrollbar>
        <el-menu :default-active="route.path" :default-openeds="route.path.startsWith('/views/') ? ['data-views'] : []" :collapse="isCollapse" :collapse-transition="false" @select="handleSelect">
          <template v-for="(items, group) in menuGroups" :key="group">
            <el-sub-menu v-if="group === '数据视图'" index="data-views">
              <template #title><el-icon><TrendCharts /></el-icon><span>{{ group }}</span></template>
              <el-menu-item v-for="item in items" :key="item.path" :index="item.path">
                <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template>
              </el-menu-item>
            </el-sub-menu>
            <template v-else>
              <p v-show="!isCollapse" class="sidebar-group-title">{{ group }}</p>
              <el-menu-item v-for="item in items" :key="item.path" :index="item.path">
                <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template>
              </el-menu-item>
            </template>
          </template>
        </el-menu>
      </el-scrollbar>
      <div v-show="!isCollapse" class="sidebar-footer"><el-icon><CircleCheck /></el-icon><span>{{ runtimeLabel }}</span></div>
    </el-aside>
    <el-container class="main-container">
      <el-header class="navbar">
        <div class="navbar-left">
          <el-button text circle :title="isCollapse ? '展开导航' : '收起导航'" :aria-label="isCollapse ? '展开导航' : '收起导航'" @click="isCollapse = !isCollapse">
            <el-icon><Expand v-if="isCollapse" /><Fold v-else /></el-icon>
          </el-button>
          <div class="navbar-title">
            <p>{{ dateLabel }}</p>
            <div><h1>管理控制台</h1><span>{{ routeMeta.title }} · {{ routeMeta.description }}</span></div>
          </div>
        </div>
        <div class="navbar-right">
          <span class="environment-pill" :class="`is-${runtimeTone}`" :title="runtimeStatus?.checkedAt || ''"><span></span>{{ runtimeEnvironment }}</span>
          <el-button text circle :title="store.theme === 'dark' ? '切换到日间模式' : '切换到夜间模式'" @click="store.toggleTheme()">
            <el-icon><Sunny v-if="store.theme === 'dark'" /><Moon v-else /></el-icon>
          </el-button>
          <el-dropdown @command="handleLogout">
            <span class="profile-dropdown"><el-avatar :size="32">{{ store.profile?.username?.slice(0, 1)?.toUpperCase() || 'A' }}</el-avatar><span>{{ store.profile?.username || '管理员' }}</span></span>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="page-main">
        <router-view v-slot="{ Component }">
          <transition name="admin-page" mode="out-in"><component :is="Component" :key="route.path" /></transition>
        </router-view>
      </el-main>
    </el-container>
    <el-alert v-if="store.toast" class="global-toast" :title="store.toast" :type="store.toastType" :closable="false" show-icon />
  </el-container>
</template>
