<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { useTicketsStore } from '../stores/tickets'
import SideNav from '../components/SideNav.vue'
import ComplianceFooter from '../components/ComplianceFooter.vue'
import { Bell, Moon, Plus, RefreshCw, Sun, UserRound } from 'lucide-vue-next'
import { getDisplayTimezone } from '../utils/helpers'

const route = useRoute()
const router = useRouter()
const store = useAppStore()
const ticketsStore = useTicketsStore()
let notificationTimer: ReturnType<typeof setInterval> | undefined

function stopNotificationPolling() {
  if (notificationTimer) clearInterval(notificationTimer)
  notificationTimer = undefined
}

function startNotificationPolling() {
  stopNotificationPolling()
  if (!store.loggedIn) return
  notificationTimer = setInterval(() => store.pollNotifications(), 45_000)
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') {
    store.pollNotifications()
    // 窗口重新聚焦时刷新工单开关（§7.3），关闭模块时及时退出工单页面。
    void ticketsStore.loadSettings().then(settings => {
      if (!settings.enabled && route.path.startsWith('/tickets')) {
        store.notify('问题反馈模块当前已关闭')
        router.push('/')
      }
    })
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  void ticketsStore.loadSettings()
  startNotificationPolling()
})

onUnmounted(() => {
  stopNotificationPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})

const routeMeta = computed(() => {
  const map: Record<string, { title: string; description: string }> = {
    '/': { title: '今日概览', description: '把注意力留给真正重要的事' },
    '/calendar': { title: '日历', description: '按时间查看个人与团队安排' },
    '/schedules': { title: '个人日程', description: '整理待办、事件与时间段任务' },
    '/teams': { title: '团队', description: '查看成员、权限和协作空间' },
    '/tasks': { title: '团队任务', description: '跟进指派、截止时间与执行状态' },
    '/notifications': { title: '通知', description: '集中处理提醒和团队动态' },
    '/tickets': { title: '问题反馈', description: '公开的问题大厅与处理进展' },
    '/fatigue/survey': { title: '疲劳调查', description: '记录今天的实际疲劳感受' },
    '/fatigue/report': { title: '疲劳回顾', description: '周 / 月疲劳与负荷回顾报告' },
    '/reminders': { title: '提醒记录', description: '查看待发送与历史提醒' },
    '/profile': { title: '个人中心', description: '管理偏好、模块和账号信息' },
    '/profile/settings': { title: '个人设置', description: '更新资料、安全与时区' },
    '/profile/fatigue': { title: '疲劳评估', description: '管理负荷模型、调查与历史' },
  }
  if (map[route.path]) return map[route.path]
  if (route.path.startsWith('/schedules/')) return { title: '日程详情', description: '查看和调整日程信息' }
  if (route.path.startsWith('/teams/')) return { title: '团队详情', description: '管理成员和团队任务' }
  if (route.path.startsWith('/tasks/')) return { title: '任务详情', description: '跟进任务进展与执行记录' }
  if (route.path.startsWith('/tickets/')) return { title: '工单详情', description: '查看问题与处理进展' }
  return map['/']
})

const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  timeZone: store.profile?.timezone || getDisplayTimezone(), month: 'long', day: 'numeric', weekday: 'long'
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
            title="查看通知"
            @click="router.push('/notifications')"
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
      <ComplianceFooter />
    </section>
    <div v-if="store.toast" class="toast">{{ store.toast }}</div>
  </main>
</template>
