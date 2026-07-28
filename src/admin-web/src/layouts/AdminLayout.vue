<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell, Calendar, Document, House, List, Timer, User, UserFilled, Fold,
  Connection, Monitor, DataAnalysis, Management,
} from '@element-plus/icons-vue'
import { useAdminStore } from '@/stores/admin'

const store = useAdminStore()
const router = useRouter()
const route = useRoute()
const isCollapse = ref(false)
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
const title = computed(() => menuItems.find(item => item.path === route.path)?.label || '管理后台')

function handleSelect(path) { router.push(path) }
function handleLogout() { store.logout(); router.replace('/login') }

onMounted(async () => {
  if (store.token && !store.profile) await store.loadProfile()
})
</script>

<template>
  <el-container v-if="store.token" class="pure-admin-layout">
    <el-aside :width="isCollapse ? '64px' : '220px'" class="sidebar">
      <div class="sidebar-logo" :class="{ collapsed: isCollapse }">
        <span class="logo-mark">D</span><strong v-show="!isCollapse">Dayliane</strong>
      </div>
      <el-scrollbar>
        <el-menu :default-active="route.path" :collapse="isCollapse" :collapse-transition="false" @select="handleSelect">
          <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template>
          </el-menu-item>
        </el-menu>
      </el-scrollbar>
    </el-aside>
    <el-container class="main-container">
      <el-header class="navbar">
        <div class="navbar-left"><el-button text circle @click="isCollapse = !isCollapse"><el-icon><Fold /></el-icon></el-button><el-breadcrumb separator="/"><el-breadcrumb-item>首页</el-breadcrumb-item><el-breadcrumb-item>{{ title }}</el-breadcrumb-item></el-breadcrumb></div>
        <div class="navbar-right">
          <el-button text circle @click="store.toggleTheme()">{{ store.theme === 'dark' ? '日' : '夜' }}</el-button>
          <el-dropdown @command="handleLogout"><span class="profile-dropdown"><el-avatar :size="30">{{ store.profile?.username?.slice(0, 1)?.toUpperCase() || 'A' }}</el-avatar><span>{{ store.profile?.username || '管理员' }}</span></span><template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div>
      </el-header>
      <el-main class="page-main"><router-view /></el-main>
    </el-container>
    <el-alert v-if="store.toast" class="global-toast" :title="store.toast" type="success" :closable="false" show-icon />
  </el-container>
</template>
