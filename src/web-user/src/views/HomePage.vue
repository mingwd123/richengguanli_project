<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import CountdownPill from '../components/CountdownPill.vue'
import { formatTime, countdown, urgency, groupByTaskState, getDisplayTimezone } from '../utils/helpers'
import type { TimelineItem } from '../types'
import {
  ArrowRight,
  CalendarDays,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  Inbox,
  Plus,
  Sparkles,
  UsersRound,
} from 'lucide-vue-next'

const store = useAppStore()
const router = useRouter()
const nowTs = ref(Date.now())
let nowTimer = 0
const collapsedModules = ref<string[]>([])
const collapsedTeamModules = ref<string[]>([])

const TIMELINE_LIMIT = 12

const greeting = computed(() => {
  const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: store.profile?.timezone || getDisplayTimezone(), hour: 'numeric', hourCycle: 'h23' }).format(new Date()))
  if (hour < 6) return '夜深了'
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const todayLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  timeZone: store.profile?.timezone || getDisplayTimezone(), month: 'long', day: 'numeric', weekday: 'long'
}).format(new Date()))

const displayTimeLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  timeZone: store.profile?.timezone || getDisplayTimezone(), hour: '2-digit', minute: '2-digit'
}).format(new Date(nowTs.value)))

const rhythmLabel = computed(() => {
  const hour = Number(new Intl.DateTimeFormat('en-US', {
    timeZone: store.profile?.timezone || getDisplayTimezone(), hour: 'numeric', hourCycle: 'h23'
  }).format(new Date(nowTs.value)))
  if (hour < 6) return '夜间收束'
  if (hour < 12) return '上午推进'
  if (hour < 18) return '下午聚焦'
  return '晚间整理'
})

const dayProgress = computed(() => {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: store.profile?.timezone || getDisplayTimezone(), hour: 'numeric', minute: 'numeric', hourCycle: 'h23'
  }).formatToParts(new Date(nowTs.value))
  const hour = Number(parts.find(part => part.type === 'hour')?.value || 0)
  const minute = Number(parts.find(part => part.type === 'minute')?.value || 0)
  return Math.min(100, Math.max(1, Math.round(((hour * 60 + minute) / 1440) * 100)))
})

function toggleModule(name: string) {
  collapsedModules.value = collapsedModules.value.includes(name)
    ? collapsedModules.value.filter(v => v !== name)
    : [...collapsedModules.value, name]
}

function toggleTeamModule(name: string) {
  collapsedTeamModules.value = collapsedTeamModules.value.includes(name)
    ? collapsedTeamModules.value.filter(v => v !== name)
    : [...collapsedTeamModules.value, name]
}

const scheduleModules = computed(() => groupByTaskState(
  store.today.personalSchedules.filter(schedule => !['completed', 'cancelled'].includes(schedule.status)),
  schedule => schedule.groupName || '未分组'
))

const teamTaskModules = computed(() => groupByTaskState(
  store.today.teamTasks.filter(task => !['completed', 'cancelled', 'all_rejected'].includes(task.status) && !['completed', 'rejected'].includes(task.assignStatus || '')),
  task => task.teamName || '团队任务'
))

const timelineEntries = computed(() => {
  const now = nowTs.value
  const items = store.timelineItems
    .map(item => normalizeTimelineEntry(item, now))
    .filter((item): item is TimelineEntryView => !!item)
    .sort((a, b) => a.sortTs - b.sortTs)

  const markerIndex = items.findIndex(item => item.sortTs >= now)
  const nowEntry: TimelineEntryView = {
    id: `now-${now}`,
    sourceType: 'now',
    kind: 'now',
    title: '现在',
    sourceLabel: '',
    sortTs: now,
    displayDate: '',
    displayTime: '',
    badgeText: '现在',
    summary: `现在 ${formatClock(now)}`,
    isDuration: false,
    isOngoing: false,
    isPast: false,
    isFuture: false,
    cardClass: 'now',
    barClass: 'now'
  } as TimelineEntryView
  if (markerIndex < 0) items.push(nowEntry)
  else items.splice(markerIndex, 0, nowEntry)
  return items.slice(0, TIMELINE_LIMIT)
})

type TimelineEntryView = TimelineItem & {
  sortTs: number
  displayDate: string
  displayTime: string
  badgeText: string
  summary: string
  isDuration: boolean
  isOngoing: boolean
  isPast: boolean
  isFuture: boolean
  cardClass: string
  barClass: string
}

function normalizeTimelineEntry(item: TimelineItem, now: number): TimelineEntryView | null {
  const durationEnd = item.endTime || item.deadlineTime
  const isDuration = item.kind === 'duration_task' && item.startTime && durationEnd
  const startTs = new Date(isDuration ? item.startTime! : item.sortAt).getTime()
  const endTs = isDuration ? new Date(durationEnd!).getTime() : startTs
  const sortTs = isDuration ? startTs : new Date(item.sortAt).getTime()
  if (Number.isNaN(sortTs) || Number.isNaN(endTs)) return null
  const dateTs = isDuration ? startTs : sortTs
  const displayDate = formatMonthDay(dateTs)
  const displayTime = isDuration ? `${formatClock(startTs)} - ${formatClock(endTs)}` : formatClock(sortTs)
  const summary = isDuration
    ? `${item.sourceLabel} · ${formatClock(startTs)}-${formatClock(endTs)}`
    : `${item.sourceLabel} · ${countdown(item.deadlineTime || item.endTime || item.startTime || item.sortAt)}`
  return {
    ...item,
    sortTs,
    displayDate,
    displayTime,
    badgeText: labelForTimelineKind(item.kind),
    summary,
    isDuration,
    isOngoing: isDuration && startTs <= now && endTs >= now,
    isPast: endTs < now,
    isFuture: startTs > now,
    cardClass: endTs < now ? 'overdue' : item.kind,
    barClass: endTs < now ? 'overdue' : item.kind
  }
}

function formatMonthDay(value: number | string) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: store.profile?.timezone || getDisplayTimezone(), month: '2-digit', day: '2-digit' }).format(new Date(value))
}

function formatClock(value: number | string) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: store.profile?.timezone || getDisplayTimezone(), hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

const upcomingWeekItems = computed(() => store.upcomingSeven.list.slice(0, 8))
function upcomingSource(item: any) { return item.sourceType === 'schedule' ? (item.groupName || '个人日程') : (item.teamName || '团队任务') }
function upcomingTime(item: any) { return item.deadlineTime || item.endTime || item.startTime || '' }

function labelForTimelineKind(kind: string) {
  if (kind === 'duration_task') return '时间段任务'
  if (kind === 'deadline_task') return '安排事项'
  if (kind === 'point_event') return '安排事项'
  if (kind === 'now') return '现在'
  return '任务'
}

function goSchedule(id: number) {
  router.push(`/schedules/${id}`)
}

function goTask(id: number) {
  router.push(`/tasks/${id}`)
}

function goTimelineItem(item: TimelineEntryView) {
  if (item.sourceType === 'schedule') goSchedule(item.id)
  if (item.sourceType === 'team_task') goTask(item.id)
}

onMounted(() => {
  nowTimer = window.setInterval(() => { nowTs.value = Date.now() }, 60000)
})
onUnmounted(() => window.clearInterval(nowTimer))

const aiPlan = ref('')
const aiLoadingPlan = ref(false)
async function loadAiPlan() {
  aiLoadingPlan.value = true
  try {
    const result = await store.aiRequest('/home/daily-plan', {})
    aiPlan.value = result.suggestion || (result as any).rawText || ''
  } catch (e: any) {
    store.notify('AI 计划加载失败: ' + (e.message || '服务不可用'))
  } finally { aiLoadingPlan.value = false }
}

function openCreateSchedule() {
  store.openScheduleModal()
  router.push('/schedules')
}
</script>

<template>
  <section class="home-layout">
    <section class="home-welcome">
      <div class="home-welcome-copy">
        <p class="home-date">{{ todayLabel }}</p>
        <h2>{{ greeting }}，{{ store.profile?.nickname || '朋友' }}</h2>
        <p>先处理最重要的一件事，剩下的交给节奏。</p>
        <div class="home-rhythm" aria-label="今日节奏状态">
          <span class="rhythm-indicator"></span>
          <strong>今日节奏已就绪</strong>
          <span class="rhythm-divider"></span>
          <span>{{ rhythmLabel }} · {{ displayTimeLabel }}</span>
        </div>
      </div>
      <div class="home-welcome-actions">
        <div class="day-progress" aria-label="今日时间进度">
          <div><span>今日时间进度</span><strong>{{ dayProgress }}%</strong></div>
          <span class="day-progress-track"><span :style="{ width: `${dayProgress}%` }"></span></span>
        </div>
        <button class="primary home-create" aria-label="添加今天的安排" title="添加今天的安排" @click="openCreateSchedule">
          <Plus :size="17" />
          <span>添加今天的安排</span>
        </button>
      </div>
    </section>

    <div class="stats-row">
      <article class="stat-card">
        <span class="stat-icon schedule"><CalendarDays :size="19" /></span>
        <div><span>待办日程</span><strong>{{ store.pendingScheduleCount }}</strong></div>
        <button class="stat-link" title="查看个人日程" @click="router.push('/schedules')"><ArrowRight :size="16" /></button>
      </article>
      <article class="stat-card">
        <span class="stat-icon task"><UsersRound :size="19" /></span>
        <div><span>团队任务</span><strong>{{ store.activeTaskCount }}</strong></div>
        <button class="stat-link" title="查看团队任务" @click="router.push('/tasks')"><ArrowRight :size="16" /></button>
      </article>
      <article class="stat-card">
        <span class="stat-icon notice"><Inbox :size="19" /></span>
        <div><span>未读通知</span><strong>{{ store.today.unreadNotificationCount }}</strong></div>
        <button class="stat-link" title="查看通知" @click="router.push('/notifications')"><ArrowRight :size="16" /></button>
      </article>
    </div>

    <section :class="['ai-plan-section', { empty: !aiPlan, loading: aiLoadingPlan }]" @click="!aiPlan && loadAiPlan()">
      <span class="ai-plan-icon"><Sparkles :size="20" /></span>
      <div class="ai-plan-body">
        <div class="ai-plan-header">
          <span>AI 今日计划</span>
          <button v-if="aiPlan" class="plain-button" :disabled="aiLoadingPlan" @click.stop="loadAiPlan">
            {{ aiLoadingPlan ? '生成中...' : '重新生成' }}
          </button>
        </div>
        <p v-if="aiPlan" class="ai-plan-content">{{ aiPlan }}</p>
        <p v-else class="ai-plan-placeholder">{{ aiLoadingPlan ? '正在整理今天的优先级...' : '生成一份结合日程与团队任务的今日建议' }}</p>
      </div>
      <ArrowRight v-if="!aiPlan && !aiLoadingPlan" class="ai-plan-arrow" :size="18" />
    </section>

    <section class="upcoming-week">
      <div class="section-head">
        <div class="section-title"><span class="section-icon time"><CalendarDays :size="18" /></span><div><h2>未来七天</h2><p>{{ store.upcomingSeven.dateFrom }} 至 {{ store.upcomingSeven.dateTo }}</p></div></div>
        <button class="plain-button section-more" @click="router.push('/calendar')">日历 <ArrowRight :size="15" /></button>
      </div>
      <div class="upcoming-week-list">
        <button v-for="item in upcomingWeekItems" :key="`${item.sourceType}-${item.id}`" @click="item.sourceType === 'schedule' ? goSchedule(item.id) : goTask(item.id)">
          <span><strong>{{ item.title }}</strong><small>{{ upcomingSource(item) }}</small></span>
          <time>{{ formatTime(upcomingTime(item)) }}</time>
        </button>
        <div v-if="!upcomingWeekItems.length" class="compact-empty"><CalendarDays :size="22" /><span>未来七天暂无安排</span></div>
      </div>
    </section>

    <div class="home-content">
      <section class="group-panel">
        <div class="section-head">
          <div class="section-title">
            <span class="section-icon"><CheckCircle2 :size="18" /></span>
            <div><h2>今天要做</h2><p>个人日程与团队任务</p></div>
          </div>
          <button class="plain-button section-more" @click="router.push('/schedules')">查看全部 <ArrowRight :size="15" /></button>
        </div>
        <p class="content-caption">个人日程</p>
        <section v-for="module in scheduleModules" :key="module.name" class="home-module">
          <div class="module-title" @click="toggleModule(module.name)">
            <button class="plain-button module-toggle">
              <ChevronRight v-if="collapsedModules.includes(module.name)" :size="15" />
              <ChevronDown v-else :size="15" />
              <span>{{ module.name }}</span>
            </button>
            <span>{{ module.items.filter(item => item.status === 'pending').length }} 项</span>
          </div>
          <template v-if="!collapsedModules.includes(module.name)">
            <article
              v-for="schedule in module.items.slice(0, 3)"
              :key="schedule.id"
              :class="['task-card', { overdue: urgency(schedule.deadlineTime || schedule.endTime || schedule.startTime) === 'danger' }]"
              @click="goSchedule(schedule.id)"
              :title="`${schedule.title}\n模块: ${schedule.groupName || '未分组'}\n状态: ${schedule.status}\n截止: ${formatTime(schedule.deadlineTime || schedule.endTime || schedule.startTime)}\n${countdown(schedule.deadlineTime || schedule.endTime || schedule.startTime)}`"
            >
              <div>
                <strong>{{ schedule.title }}</strong>
                <small>{{ formatTime(schedule.deadlineTime || schedule.endTime || schedule.startTime) }}</small>
              </div>
              <CountdownPill :time="schedule.deadlineTime || schedule.endTime || schedule.startTime" :created-at="schedule.createdAt" :start-time="schedule.startTime" :end-time="schedule.endTime" :deadline-time="schedule.deadlineTime" :remind-at="schedule.remindAt" />
            </article>
            <p v-if="module.items.length > 3" class="muted module-more">还有 {{ module.items.length - 3 }} 项日程</p>
          </template>
        </section>
        <div v-if="scheduleModules.length === 0" class="compact-empty"><CalendarDays :size="22" /><span>今天还没有个人日程</span></div>

        <p class="content-caption team-caption">团队任务</p>
        <section v-for="module in teamTaskModules" :key="`team-${module.name}`" class="home-module">
          <div class="module-title" @click="toggleTeamModule(module.name)">
            <button class="plain-button module-toggle">
              <ChevronRight v-if="collapsedTeamModules.includes(module.name)" :size="15" />
              <ChevronDown v-else :size="15" />
              <span>{{ module.name }}</span>
            </button>
            <span>{{ module.items.length }} 项</span>
          </div>
          <template v-if="!collapsedTeamModules.includes(module.name)">
            <article
              v-for="task in module.items.slice(0, 3)"
              :key="task.id"
              :class="['task-card', { overdue: urgency(task.deadlineTime || task.startTime) === 'danger' }]"
              @click="goTask(task.id)"
              :title="`${task.title}\n团队: ${task.teamName || ''}\n状态: ${task.assignStatus || task.status}\n截止: ${formatTime(task.deadlineTime || task.startTime)}\n${countdown(task.deadlineTime || task.startTime)}`"
            >
              <div>
                <strong>{{ task.title }}</strong>
                <small>{{ task.groupName || '团队任务' }} · {{ formatTime(task.deadlineTime || task.startTime) }}</small>
              </div>
              <CountdownPill :time="task.deadlineTime || task.startTime" :created-at="task.createdAt" :start-time="task.startTime" :deadline-time="task.deadlineTime" :remind-at="task.remindAt" />
            </article>
            <p v-if="module.items.length > 3" class="muted module-more">还有 {{ module.items.length - 3 }} 项团队任务</p>
          </template>
        </section>
        <div v-if="teamTaskModules.length === 0" class="compact-empty"><UsersRound :size="22" /><span>暂时没有待处理的团队任务</span></div>
      </section>

      <aside class="timeline-panel">
        <div class="timeline-head">
          <div class="section-title">
            <span class="section-icon time"><Clock3 :size="18" /></span>
            <div>
              <h2>时间轴</h2>
              <p>{{ store.timelineStats.today }} 项在今天 · {{ store.timelineStats.overdue }} 项逾期</p>
            </div>
          </div>
          <button class="plain-button section-more" @click="router.push('/calendar')">日历 <ArrowRight :size="15" /></button>
        </div>
        <div class="timeline-track timeline-list">
          <div class="timeline-axis"></div>
          <article
            v-for="item in timelineEntries"
            :key="`${item.sourceType}-${item.id}-${item.assignRound || 0}`"
            :class="['timeline-entry', item.barClass === 'overdue' ? 'timeline-overdue' : item.barClass, { ongoing: item.isOngoing, past: item.isPast, now: item.kind === 'now' }]"
            @click="goTimelineItem(item)"
          >
            <div class="timeline-left">
              <span v-if="item.kind === 'now'" class="timeline-time now">现在</span>
              <template v-else>
                <span class="timeline-time">{{ item.displayTime }}</span>
                <span class="timeline-date">{{ item.displayDate }}</span>
              </template>
            </div>
            <div class="timeline-dot-wrap">
              <span v-if="item.kind === 'now'" class="timeline-now-dot"></span>
              <span v-else-if="item.isDuration" :class="['duration-bar', { ongoing: item.isOngoing }]"></span>
              <span v-else :class="['line-dot', item.barClass === 'overdue' ? 'timeline-dot-overdue' : item.barClass]"></span>
            </div>
            <div :class="['timeline-card', { 'timeline-overdue': item.barClass === 'overdue' }]">
              <div class="timeline-card-top">
                <span :class="['kind-chip', item.barClass === 'overdue' ? item.kind : item.barClass]">{{ item.badgeText }}</span>
                <span class="timeline-summary-text">{{ item.summary }}</span>
              </div>
              <strong>{{ item.title }}</strong>
              <small>{{ item.sourceLabel }}</small>
            </div>
          </article>
        </div>
        <div v-if="timelineEntries.length <= 1" class="compact-empty"><Clock3 :size="22" /><span>时间轴还是空的</span></div>
      </aside>
    </div>
  </section>
</template>
