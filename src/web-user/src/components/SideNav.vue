<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { useAppStore } from '../stores/app'
import {
  CalendarDays,
  CalendarRange,
  CheckSquare2,
  House,
  LogOut,
  PanelsTopLeft,
  Settings2,
  UsersRound,
} from 'lucide-vue-next'

const router = useRouter()
const route = useRoute()
const store = useAppStore()

const nav = [
  { id: 'home', label: '首页', icon: House, route: '/' },
  { id: 'calendar', label: '日历', icon: CalendarDays, route: '/calendar' },
  { id: 'schedules', label: '个人日程', icon: CalendarRange, route: '/schedules' },
  { id: 'teams', label: '团队', icon: UsersRound, route: '/teams' },
  { id: 'tasks', label: '团队任务', icon: CheckSquare2, route: '/tasks' },
  { id: 'notifications', label: '通知', icon: PanelsTopLeft, route: '/notifications' },
  { id: 'profile', label: '我的', icon: Settings2, route: '/profile' },
]

const labels = {
  user: '用户',
  logout: '退出登录',
}

function isActive(item: typeof nav[number]) {
  if (item.route === '/') return route.path === '/'
  return route.path.startsWith(item.route)
}

function navigate(item: typeof nav[number]) {
  router.push(item.route)
}

function handleLogout() {
  store.logout()
  router.push('/login')
}
</script>

<template>
  <aside class="side-nav">
    <div class="logo-area">
      <span class="logo-icon">D</span>
      <div class="brand-lockup">
        <strong>Dayliane</strong>
        <small>Make time visible</small>
      </div>
    </div>
    <p class="nav-caption">工作台</p>
    <nav class="nav-items">
      <button
        v-for="item in nav"
        :key="item.id"
        :class="{ active: isActive(item) }"
        :title="item.label"
        @click="navigate(item)"
      >
        <span class="nav-icon"><component :is="item.icon" :size="18" :stroke-width="1.8" /></span>
        <span class="nav-label">{{ item.label }}</span>
        <span v-if="item.id === 'notifications' && store.today.unreadNotificationCount" class="nav-badge">
          {{ Math.min(store.today.unreadNotificationCount, 99) }}
        </span>
      </button>
    </nav>
    <div class="user-info">
      <button class="user-summary" title="个人中心" @click="router.push('/profile')">
        <span class="user-avatar">{{ store.profile?.nickname?.slice(0, 1) || 'U' }}</span>
        <span class="user-copy">
          <strong>{{ store.profile?.nickname || labels.user }}</strong>
          <small>{{ store.profile?.phone }}</small>
        </span>
      </button>
      <button class="icon-button logout-button" :title="labels.logout" :aria-label="labels.logout" @click="handleLogout">
        <LogOut :size="17" />
      </button>
    </div>
  </aside>
</template>
