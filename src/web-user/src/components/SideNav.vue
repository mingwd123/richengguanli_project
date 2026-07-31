<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAppStore } from '../stores/app'
import {
  CalendarDays,
  CalendarRange,
  AlarmClock,
  CheckSquare2,
  House,
  LogOut,
  MoreHorizontal,
  PanelsTopLeft,
  Settings2,
  UsersRound,
} from 'lucide-vue-next'

const router = useRouter()
const route = useRoute()
const store = useAppStore()
const mobileMoreOpen = ref(false)

const nav = [
  { id: 'home', label: '首页', icon: House, route: '/', mobilePrimary: true },
  { id: 'calendar', label: '日历', icon: CalendarDays, route: '/calendar', mobilePrimary: true },
  { id: 'schedules', label: '个人日程', icon: CalendarRange, route: '/schedules', mobilePrimary: true },
  { id: 'teams', label: '团队', icon: UsersRound, route: '/teams', mobilePrimary: false },
  { id: 'tasks', label: '团队任务', icon: CheckSquare2, route: '/tasks', mobilePrimary: true },
  { id: 'notifications', label: '通知', icon: PanelsTopLeft, route: '/notifications', mobilePrimary: false },
  { id: 'reminders', label: '提醒记录', icon: AlarmClock, route: '/reminders', mobilePrimary: false },
  { id: 'profile', label: '我的', icon: Settings2, route: '/profile', mobilePrimary: false },
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
  mobileMoreOpen.value = false
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
        :class="['nav-entry', { active: isActive(item), 'mobile-primary': item.mobilePrimary }]"
        :title="item.label"
        @click="navigate(item)"
      >
        <span class="nav-icon"><component :is="item.icon" :size="18" :stroke-width="1.8" /></span>
        <span class="nav-label">{{ item.label }}</span>
        <span v-if="item.id === 'notifications' && store.today.unreadNotificationCount" class="nav-badge">
          {{ Math.min(store.today.unreadNotificationCount, 99) }}
        </span>
      </button>
      <button
        :class="['mobile-more-button', { active: nav.filter(item => !item.mobilePrimary).some(isActive) }]"
        title="更多"
        aria-label="更多导航"
        :aria-expanded="mobileMoreOpen"
        @click="mobileMoreOpen = !mobileMoreOpen"
      >
        <span class="nav-icon"><MoreHorizontal :size="19" :stroke-width="1.8" /></span>
        <span class="nav-label">更多</span>
      </button>
    </nav>
    <div class="user-info">
      <button class="user-summary" title="个人中心" @click="router.push('/profile')">
        <span class="user-avatar"><img v-if="store.profile?.avatarUrl" :src="store.profile.avatarUrl" alt="" /><template v-else>{{ store.profile?.nickname?.slice(0, 1) || 'U' }}</template></span>
        <span class="user-copy">
          <strong>{{ store.profile?.nickname || labels.user }}</strong>
          <small>{{ store.profile?.phone }}</small>
        </span>
      </button>
      <button class="icon-button logout-button" :title="labels.logout" :aria-label="labels.logout" @click="handleLogout">
        <LogOut :size="17" />
      </button>
    </div>
    <div v-if="mobileMoreOpen" class="mobile-more-backdrop" @click="mobileMoreOpen = false"></div>
    <section v-if="mobileMoreOpen" class="mobile-more-menu" aria-label="更多导航">
      <button v-for="item in nav.filter(entry => !entry.mobilePrimary)" :key="item.id" :class="{ active: isActive(item) }" @click="navigate(item)">
        <component :is="item.icon" :size="18" />
        <span>{{ item.label }}</span>
        <span v-if="item.id === 'notifications' && store.today.unreadNotificationCount" class="nav-badge">{{ Math.min(store.today.unreadNotificationCount, 99) }}</span>
      </button>
    </section>
  </aside>
</template>
