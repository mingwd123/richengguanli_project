<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { useAppStore } from '../stores/app'

const router = useRouter()
const route = useRoute()
const store = useAppStore()

const nav = [
  { id: 'home', label: '首页', icon: 'H', route: '/' },
  { id: 'calendar', label: '日历', icon: 'C', route: '/calendar' },
  { id: 'schedules', label: '个人日程', icon: 'S', route: '/schedules' },
  { id: 'teams', label: '团队', icon: 'T', route: '/teams' },
  { id: 'tasks', label: '团队任务', icon: 'K', route: '/tasks' },
  { id: 'notifications', label: '通知', icon: 'N', route: '/notifications' },
  { id: 'profile', label: '我的', icon: 'P', route: '/profile' },
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
    <div class="logo-area"><span class="logo-icon">D</span><strong>Dayliane</strong></div>
    <nav class="nav-items">
      <button
        v-for="item in nav"
        :key="item.id"
        :class="{ active: isActive(item) }"
        @click="navigate(item)"
      >
        <span>{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>
    <div class="user-info">
      <strong>{{ store.profile?.nickname || labels.user }}</strong>
      <small>{{ store.profile?.phone }}</small>
      <button @click="handleLogout">{{ labels.logout }}</button>
    </div>
  </aside>
</template>
