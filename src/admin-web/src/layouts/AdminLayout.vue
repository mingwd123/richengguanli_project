<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell, Calendar, CircleCheck, Connection, DataAnalysis, Document, Expand,
  Fold, House, List, Management, Moon, Sunny, Timer, User, UserFilled,
} from '@element-plus/icons-vue'
import { useAdminStore } from '@/stores/admin'

const store = useAdminStore()
const router = useRouter()
const route = useRoute()
const compactMedia = window.matchMedia('(max-width: 900px)')
const isCollapse = ref(compactMedia.matches)
const menuItems = [
  { path: '/', label: '仪表盘', icon: House },
  { path: '/users', label: '用户管理', icon: User },
  { path: '/teams', label: '团队管理', icon: UserFilled },
  { path: '/schedules', label: '日程管理', icon: Calendar },
  { path: '/team-tasks', label: '团队任务', icon: List },
  { path: '/notifications', label: '通知记录', icon: Bell },
  { path: '/reminders', label: '提醒记录', icon: Timer },
  { path: '/admin-users', label: '管理员', icon: Management },
  { path: '/operation-logs', label: '操作日志', icon: Document },
  { path: '/ai-config', label: 'AI 配置', icon: Connection },
  { path: '/ai-logs', label: 'AI 调用记录', icon: DataAnalysis },
]
const routeMeta = computed(() => {
  const descriptions = {
    '/': '查看系统资源和管理入口',
    '/users': '检索用户并管理账号状态',
    '/teams': '查看团队、成员和运行状态',
    '/schedules': '审查全站个人日程记录',
    '/team-tasks': '跟踪团队任务和执行状态',
    '/notifications': '查看站内通知发送记录',
    '/reminders': '检查提醒任务与投递状态',
    '/admin-users': '维护后台管理员账号',
    '/operation-logs': '追踪后台敏感操作记录',
    '/ai-config': '管理 AI 服务提供商配置',
    '/ai-logs': '审查 AI 功能调用明细',
  }
  return {
    title: menuItems.find(item => item.path === route.path)?.label || '管理后台',
    description: descriptions[route.path] || 'Dayliane 管理控制台',
  }
})
const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: 'long', day: 'numeric', weekday: 'long'
}).format(new Date()))

function handleSelect(path) { router.push(path) }
function handleLogout() { store.logout(); router.replace('/login') }
function handleMediaChange(event) { if (event.matches) isCollapse.value = true }

onMounted(async () => {
  compactMedia.addEventListener('change', handleMediaChange)
  if (store.token && !store.profile) await store.loadProfile()
})
onUnmounted(() => compactMedia.removeEventListener('change', handleMediaChange))
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
        <el-menu :default-active="route.path" :collapse="isCollapse" :collapse-transition="false" @select="handleSelect">
          <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template>
          </el-menu-item>
        </el-menu>
      </el-scrollbar>
      <div v-show="!isCollapse" class="sidebar-footer"><el-icon><CircleCheck /></el-icon><span>系统运行中</span></div>
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
          <span class="environment-pill"><span></span>本地环境</span>
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
    <el-alert v-if="store.toast" class="global-toast" :title="store.toast" type="success" :closable="false" show-icon />
  </el-container>
</template>
