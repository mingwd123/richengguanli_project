<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Moon, Plus, RefreshCw, Sun } from 'lucide-vue-next'
import SideNav from '@web/components/SideNav.vue'
import { useAppStore } from '@web/stores/app'
import type { Notification } from '@web/types'
import { getDisplayTimezone } from '@web/utils/helpers'
import {
  listenForDesktopNavigation,
  nativeNotificationGranted,
  sendNativeNotification,
  setPendingSurveyTray,
} from '../services/native'
import { useDesktopShell } from '../stores/desktopShell'
import DesktopQuickTimeline from '../components/DesktopQuickTimeline.vue'
import type { DesktopNavigationIntent, StoredNotificationNavigation } from '../utils/desktopNavigation'

const route = useRoute()
const router = useRouter()
const store = useAppStore()
const shell = useDesktopShell()
const SHOWN_NATIVE_IDS_KEY = 'dayliane_desktop_notice_ids'
let notificationTimer: ReturnType<typeof setInterval> | undefined
let stopDesktopNavigation: (() => void | Promise<void>) | undefined
let refreshPromise: Promise<void> | null = null

const routeMeta = computed(() => {
  const map: Record<string, { title: string; description: string }> = {
    '/': { title: '今日概览', description: '把注意力留给真正重要的事' },
    '/calendar': { title: '日历', description: '按时间查看个人与团队安排' },
    '/schedules': { title: '个人日程', description: '整理待办、事件与时间段任务' },
    '/teams': { title: '团队', description: '查看成员、权限和协作空间' },
    '/tasks': { title: '团队任务', description: '跟进指派、截止时间与执行状态' },
    '/notifications': { title: '通知', description: '集中处理提醒和团队动态' },
    '/fatigue/survey': { title: '疲劳调查', description: '记录今天的实际疲劳感受' },
    '/reminders': { title: '提醒记录', description: '查看待发送与历史提醒' },
    '/profile': { title: '个人中心', description: '管理偏好、模块和账号信息' },
    '/profile/settings': { title: '个人设置', description: '更新资料、安全与时区' },
    '/profile/notifications': { title: '通知设置', description: '选择需要接收的消息' },
    '/profile/fatigue': { title: '疲劳评估设置', description: '管理负荷模型、调查与历史' },
  }
  if (map[route.path]) return map[route.path]
  if (route.path.startsWith('/schedules/')) return { title: '日程详情', description: '查看和调整日程信息' }
  if (route.path.startsWith('/teams/')) return { title: '团队详情', description: '管理成员和团队任务' }
  if (route.path.startsWith('/tasks/')) return { title: '任务详情', description: '跟进任务进展与执行记录' }
  return map['/']
})

const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  timeZone: store.profile?.timezone || getDisplayTimezone(),
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}).format(new Date()))

function openCreateSchedule() {
  store.openScheduleModal()
  if (route.path !== '/schedules') router.push('/schedules')
}

async function deliverNativeNotifications(items: Notification[]) {
  if (!shell.nativeNotificationsEnabled.value || !(await nativeNotificationGranted())) return
  let shownIds: Set<number>
  try {
    shownIds = new Set(JSON.parse(localStorage.getItem(SHOWN_NATIVE_IDS_KEY) || '[]') as number[])
  } catch {
    shownIds = new Set()
  }
  for (const item of items.filter(item => !item.isRead && !shownIds.has(item.id))) {
    try {
      if (await sendNativeNotification(item)) shownIds.add(item.id)
    } catch {
      // Native delivery is supplementary; the in-app notification remains available.
    }
  }
  localStorage.setItem(SHOWN_NATIVE_IDS_KEY, JSON.stringify([...shownIds].slice(-200)))
}

async function pollNotifications() {
  await store.pollNotifications()
  await deliverNativeNotifications(store.notifications)
}

async function refreshDesktopState() {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    await Promise.allSettled([pollNotifications(), store.loadFatigueSurveyToday()])
    await setPendingSurveyTray(Boolean(store.fatigueSurveyToday?.pending))
    shell.requestQuickRefresh()
  })().finally(() => { refreshPromise = null })
  return refreshPromise
}

async function handleDesktopNavigation(intent: DesktopNavigationIntent) {
  const storedIntent = intent as StoredNotificationNavigation
  if (storedIntent.userId && storedIntent.userId !== store.profile?.id) return
  if ('notificationId' in intent && intent.notificationId) void store.readNotification(intent.notificationId)

  if (intent.kind === 'refresh') {
    await refreshDesktopState()
    return
  }
  if (intent.kind === 'quick-timeline') {
    shell.requestQuickTarget({ kind: 'timeline' })
    await shell.setQuickTimeline(true)
    return
  }
  if (intent.kind === 'fatigue-survey') {
    shell.requestQuickTarget({ kind: 'fatigue-survey', localDate: intent.localDate })
    await shell.setQuickTimeline(true)
    return
  }

  const switched = await shell.setQuickTimeline(false)
  if (switched === false) return
  if (intent.kind === 'route') await router.push(intent.targetRoute)
  else if (intent.targetRoute) await router.push(intent.targetRoute)
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') refreshDesktopState()
}

function handleOnline() {
  refreshDesktopState()
}

function handleNotificationButton() {
  router.push('/notifications')
}

onMounted(async () => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('online', handleOnline)
  stopDesktopNavigation = await listenForDesktopNavigation(handleDesktopNavigation)
  refreshDesktopState()
  notificationTimer = setInterval(refreshDesktopState, 45_000)
})

onUnmounted(() => {
  if (notificationTimer) clearInterval(notificationTimer)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('online', handleOnline)
  void stopDesktopNavigation?.()
  void setPendingSurveyTray(false)
})
</script>

<template>
  <main :class="['app-frame', 'desktop-app-frame', { 'desktop-quick-layout': shell.quickTimeline.value }]">
    <DesktopQuickTimeline v-if="shell.quickTimeline.value" />
    <template v-else>
      <SideNav />
      <section class="main-area desktop-main-area">
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
          <button class="icon-button notification-button" title="通知" @click="handleNotificationButton">
            <Bell :size="18" />
            <span v-if="store.today.unreadNotificationCount" class="action-badge">{{ Math.min(store.today.unreadNotificationCount, 99) }}</span>
          </button>
          <button class="icon-button" title="刷新数据" :disabled="store.loading" @click="store.loadAll">
            <RefreshCw :class="{ spinning: store.loading }" :size="18" />
          </button>
          <button class="primary create-button" aria-label="新建日程" title="新建日程" @click="openCreateSchedule">
            <Plus :size="17" /><span>新建日程</span>
          </button>
        </div>
      </header>
      <router-view v-slot="{ Component }">
        <transition name="page-fade" mode="out-in">
          <component :is="Component" :key="route.path" />
        </transition>
      </router-view>
      </section>
    </template>
    <div v-if="store.toast" class="toast">{{ store.toast }}</div>
  </main>
</template>
