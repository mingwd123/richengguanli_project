<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import SideNav from '../components/SideNav.vue'
import { Bell, Moon, Plus, RefreshCw, Sun, UserRound } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const store = useAppStore()

const routeMeta = computed(() => {
  const map: Record<string, { title: string; description: string }> = {
    '/': { title: '今日概览', description: '把注意力留给真正重要的事' },
    '/calendar': { title: '日历', description: '按时间查看个人与团队安排' },
    '/schedules': { title: '个人日程', description: '整理待办、事件与时间段任务' },
    '/teams': { title: '团队', description: '查看成员、权限和协作空间' },
    '/tasks': { title: '团队任务', description: '跟进指派、截止时间与执行状态' },
    '/notifications': { title: '通知', description: '集中处理提醒和团队动态' },
    '/profile': { title: '个人中心', description: '管理偏好、模块和账号信息' },
    '/profile/settings': { title: '个人设置', description: '更新资料、安全与时区' },
  }
  if (map[route.path]) return map[route.path]
  if (route.path.startsWith('/schedules/')) return { title: '日程详情', description: '查看和调整日程信息' }
  if (route.path.startsWith('/teams/')) return { title: '团队详情', description: '管理成员和团队任务' }
  if (route.path.startsWith('/tasks/')) return { title: '任务详情', description: '跟进任务进展与执行记录' }
  return map['/']
})

const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: 'long', day: 'numeric', weekday: 'long'
}).format(new Date()))

function openCreateSchedule() {
  store.openScheduleModal()
  if (route.path !== '/schedules') router.push('/schedules')
}
</script>

<template>
  <main class="app-frame">
    <SideNav />
    <section class="main-area">
      <header class="page-topbar">
        <div class="page-heading">
          <p class="topbar-date">{{ dateLabel }}</p>
          <div class="title-line">
            <h1>{{ routeMeta.title }}</h1>
            <span class="title-description">{{ routeMeta.description }}</span>
          </div>
        </div>
        <div class="top-actions">
          <button class="icon-button" :title="store.theme === 'dark' ? '切换到日间模式' : '切换到夜间模式'" @click="store.toggleTheme">
            <Sun v-if="store.theme === 'dark'" :size="18" />
            <Moon v-else :size="18" />
          </button>
          <button
            class="icon-button notification-button"
            :title="store.browserNoticePermission === 'granted' ? '查看通知' : '开启系统通知'"
            @click="store.browserNoticePermission === 'granted' ? router.push('/notifications') : store.requestBrowserNoticePermission()"
          >
            <Bell :size="18" />
            <span v-if="store.today.unreadNotificationCount" class="action-badge">{{ Math.min(store.today.unreadNotificationCount, 99) }}</span>
          </button>
          <button class="icon-button desktop-only" title="刷新数据" :disabled="store.loading" @click="store.loadAll">
            <RefreshCw :class="{ spinning: store.loading }" :size="18" />
          </button>
          <button class="profile-shortcut mobile-only" title="个人中心" @click="router.push('/profile')">
            <UserRound :size="18" />
          </button>
          <button class="primary create-button" aria-label="新建日程" title="新建日程" @click="openCreateSchedule">
            <Plus :size="17" />
            <span>新建日程</span>
          </button>
        </div>
      </header>
      <router-view v-slot="{ Component }">
        <transition name="page-fade" mode="out-in">
          <component :is="Component" :key="route.path" />
        </transition>
      </router-view>
    </section>
    <div v-if="store.toast" class="toast">{{ store.toast }}</div>
  </main>
</template>
