<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import CountdownPill from '../components/CountdownPill.vue'
import { formatTime, countdown, isOverdue, groupByTaskState, getDisplayTimezone } from '../utils/helpers'
import type { Schedule, ScheduleViewMode, TimelineItem } from '../types'
import { buildTodayScheduleSections } from '../utils/scheduleViews'
import { timelinePresentationStatus, timelineTimeRange } from '../utils/timeline'
import {
  ArrowRight,
  AlertTriangle,
  BatteryMedium,
  CalendarDays,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  Inbox,
  LoaderCircle,
  Layers3,
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
const completingScheduleId = ref<number | null>(null)

const scheduleViewOptions = [
  { value: 'time' as ScheduleViewMode, label: '时间', icon: Clock3 },
  { value: 'group' as ScheduleViewMode, label: '分组', icon: Layers3 },
  { value: 'urgency' as ScheduleViewMode, label: '紧急度', icon: AlertTriangle },
  { value: 'fatigue' as ScheduleViewMode, label: '疲劳度', icon: BatteryMedium },
]

const TIMELINE_LIMIT = 12

const fatigueSummary = computed(() => store.fatigueDaily)
const fatigueSurveyPending = computed(() => Boolean(store.fatigueSurveyToday?.pending))
const fatigueNeedsAlertAction = computed(() => Number(fatigueSummary.value?.predictedScore || 0) >= 70 || Number(fatigueSummary.value?.actualLoadScore || 0) >= 70)
const fatigueStateLabel = computed(() => {
  const labels: Record<string, string> = {
    comfortable: '舒适',
    full: '较满',
    tired: '较疲劳',
    high: '高负荷',
    overloaded: '可能过载',
  }
  return labels[fatigueSummary.value?.level || ''] || '暂无估算'
})

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

const scheduleModules = computed(() => buildTodayScheduleSections(
  store.today.personalSchedules,
  store.viewMode,
  store.taskGroups,
  nowTs.value
))

const teamTaskModules = computed(() => groupByTaskState(
  store.today.teamTasks.filter(task => ['active', 'unassigned'].includes(task.status) && !['completed', 'rejected'].includes(task.assignStatus || '')),
  task => task.teamName || '团队任务'
))

const timelineEntries = computed(() => {
  const now = nowTs.value
  const items = store.timelineItems
    .map(item => normalizeTimelineEntry(item, now))
    .filter((item): item is TimelineEntryView => !!item)
    .sort((a, b) => a.sortTs - b.sortTs)

  const markerIndex = items.findIndex(item => item.sortTs >= now)
  const firstVisibleIndex = markerIndex < 0 ? Math.max(0, items.length - (TIMELINE_LIMIT - 1)) : Math.max(0, markerIndex - 4)
  const visibleItems = items.slice(firstVisibleIndex, firstVisibleIndex + TIMELINE_LIMIT - 1)
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
  const nowIndex = visibleItems.findIndex(item => item.sortTs >= now)
  visibleItems.splice(nowIndex < 0 ? visibleItems.length : nowIndex, 0, nowEntry)
  return visibleItems
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
  presentation: ReturnType<typeof timelinePresentationStatus>
  cardClass: string
  barClass: string
}

function normalizeTimelineEntry(item: TimelineItem, now: number): TimelineEntryView | null {
  const range = timelineTimeRange(item)
  const isDuration = range.isDuration
  const startTs = new Date(range.startAt).getTime()
  const endTs = new Date(range.endAt).getTime()
  const sortTs = new Date(range.sortAt).getTime()
  if (Number.isNaN(sortTs) || Number.isNaN(endTs)) return null
  const dateTs = isDuration ? startTs : sortTs
  const displayDate = formatMonthDay(dateTs)
  const displayTime = isDuration ? `${formatClock(startTs)} - ${formatClock(endTs)}` : formatClock(sortTs)
  const presentation = timelinePresentationStatus(item, now)
  const defaultSummary = isDuration
    ? `${item.sourceLabel} · ${formatClock(startTs)}-${formatClock(endTs)}`
    : `${item.sourceLabel} · ${countdown(item.deadlineTime || item.endTime || item.startTime || item.sortAt, item.kind)}`
  const summary = presentation === 'past'
    ? `${item.sourceLabel} - 已过`
    : presentation === 'active'
      ? `${item.sourceLabel} - 进行中至 ${formatClock(endTs)}`
      : defaultSummary
  return {
    ...item,
    sortTs,
    displayDate,
    displayTime,
    badgeText: labelForTimelineKind(item.kind),
    summary,
    isDuration,
    isOngoing: presentation === 'active',
    isPast: presentation === 'past',
    isFuture: presentation === 'upcoming',
    presentation,
    cardClass: presentation === 'overdue' ? 'overdue' : item.kind,
    barClass: presentation === 'overdue' ? 'overdue' : item.kind
  }
}

function schedulePresentationClasses(schedule: any) {
  const presentation = timelinePresentationStatus({ ...schedule, sourceType: 'schedule' }, nowTs.value)
  return { overdue: presentation === 'overdue', past: presentation === 'past' }
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
  if (kind === 'deadline_task') return '截止任务'
  if (kind === 'duration_task') return '时间段任务'
  if (kind === 'deadline_task') return '安排事项'
  if (kind === 'point_event') return '安排事项'
  if (kind === 'now') return '现在'
  return '任务'
}

function goSchedule(id: number) {
  router.push({ name: 'ScheduleDetail', params: { id }, query: { from: 'home' } })
}

function goTask(id: number) {
  router.push(`/tasks/${id}`)
}

function goTimelineItem(item: TimelineEntryView) {
  if (item.sourceType === 'schedule') goSchedule(item.id)
  if (item.sourceType === 'team_task') goTask(item.id)
}

async function completeSchedule(schedule: Pick<Schedule, 'id'>) {
  if (completingScheduleId.value !== null) return
  completingScheduleId.value = schedule.id
  try {
    if (await store.setScheduleStatus(schedule, 'complete')) store.notify('日程已完成')
  } finally {
    completingScheduleId.value = null
  }
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

function openFatigueSurvey() {
  router.push('/fatigue/survey')
}

async function suppressFatigueAlerts() {
  await store.suppressFatigueAlertsToday(fatigueSummary.value?.level)
}

async function applyScheduleView(mode: ScheduleViewMode) {
  if (store.viewMode === mode) return
  collapsedModules.value = []
  await store.setScheduleViewMode(mode)
}

function openSchedules() {
  router.push({ path: '/schedules', query: { view: store.viewMode } })
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
      <article class="stat-card fatigue-stat-card">
        <span class="stat-icon fatigue"><BatteryMedium :size="19" /></span>
        <div><span>个人预计负荷</span><strong>{{ fatigueSummary?.predictedScore ?? '--' }}</strong><small>{{ fatigueStateLabel }} · {{ fatigueSummary?.plannedLoad ?? 0 }} / {{ fatigueSummary?.capacity75 ?? store.fatigueProfile?.capacity75 ?? 18 }}</small></div>
        <button class="stat-link" :title="fatigueSurveyPending ? '填写疲劳调查' : '查看疲劳调查'" @click="openFatigueSurvey"><ArrowRight :size="16" /></button>
      </article>
    </div>

    <section class="home-fatigue-strip" :class="{ pending: fatigueSurveyPending }">
      <span class="home-fatigue-icon"><BatteryMedium :size="18" /></span>
      <div class="home-fatigue-copy">
        <strong>{{ fatigueSurveyPending ? '今天的实际疲劳还没有记录' : '个人日程负荷摘要' }}</strong>
        <span v-if="fatigueSurveyPending">完成一项个人日程后，记录你的真实感受可以帮助模型逐步校准。</span>
        <span v-else>预计 {{ fatigueSummary?.predictedScore ?? 0 }} 分 · 已完成负荷 {{ fatigueSummary?.completedLoad ?? 0 }} · {{ fatigueSummary?.modelStage === 'default' ? '默认模型' : fatigueSummary?.modelStage || '校准中' }}</span>
      </div>
      <div class="home-fatigue-actions">
        <button v-if="fatigueNeedsAlertAction && !fatigueSurveyPending" class="plain-button" @click="suppressFatigueAlerts">今天不再提醒</button>
        <button class="plain-button" @click="openFatigueSurvey">{{ fatigueSurveyPending ? '填写调查' : '查看详情' }} <ArrowRight :size="15" /></button>
      </div>
    </section>

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
          <button class="plain-button section-more" @click="openSchedules">查看全部 <ArrowRight :size="15" /></button>
        </div>
        <div class="home-schedule-view-toolbar">
          <p class="content-caption">个人日程</p>
          <div class="segmented-control home-schedule-view-control" aria-label="首页个人日程视图">
            <button
              v-for="option in scheduleViewOptions"
              :key="option.value"
              type="button"
              :class="{ active: store.viewMode === option.value }"
              :aria-pressed="store.viewMode === option.value"
              @click="applyScheduleView(option.value)"
            >
              <component :is="option.icon" :size="15" />{{ option.label }}
            </button>
          </div>
        </div>
        <section v-for="module in scheduleModules" :key="module.key" class="home-module">
          <div class="module-title" @click="toggleModule(module.key)">
            <button class="plain-button module-toggle">
              <ChevronRight v-if="collapsedModules.includes(module.key)" :size="15" />
              <ChevronDown v-else :size="15" />
              <span>{{ module.name }}</span>
            </button>
            <span>{{ module.items.length }} 项</span>
          </div>
          <template v-if="!collapsedModules.includes(module.key)">
            <article
              v-for="schedule in module.items.slice(0, 3)"
              :key="schedule.id"
              :class="['task-card', 'home-schedule-card', schedulePresentationClasses(schedule)]"
              @click="goSchedule(schedule.id)"
              :title="`${schedule.title}\n模块: ${schedule.groupName || '未分组'}\n状态: ${schedule.status}\n时间: ${formatTime(schedule.deadlineTime || schedule.endTime || schedule.startTime)}\n${countdown(schedule.deadlineTime || schedule.endTime || schedule.startTime, schedule.timeType)}`"
            >
              <div>
                <strong>{{ schedule.title }}</strong>
                <small>{{ formatTime(schedule.deadlineTime || schedule.endTime || schedule.startTime) }}</small>
              </div>
              <CountdownPill :time="schedule.deadlineTime || schedule.endTime || schedule.startTime" :time-type="schedule.timeType" :created-at="schedule.createdAt" :start-time="schedule.startTime" :end-time="schedule.endTime" :deadline-time="schedule.deadlineTime" :remind-at="schedule.remindAt" />
              <button type="button" class="home-schedule-complete" :disabled="completingScheduleId !== null" title="完成日程" aria-label="完成日程" @click.stop="completeSchedule(schedule)">
                <LoaderCircle v-if="completingScheduleId === schedule.id" class="spinning" :size="16" />
                <Check v-else :size="16" />
              </button>
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
              :class="['task-card', { overdue: isOverdue(task) }]"
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
              <span v-else-if="item.isDuration" :class="['timeline-duration-marker', { ongoing: item.isOngoing }]">
                <span class="timeline-duration-node start"></span>
                <span class="timeline-duration-rail"></span>
                <span class="timeline-duration-node end"></span>
              </span>
              <span v-else-if="item.kind === 'deadline_task'" class="timeline-deadline-node"></span>
              <span v-else :class="['line-dot', item.barClass === 'overdue' ? 'timeline-dot-overdue' : item.barClass]"></span>
            </div>
            <div :class="['timeline-card', { 'timeline-overdue': item.barClass === 'overdue', 'has-complete': item.sourceType === 'schedule' && item.status === 'pending' }]">
              <div class="timeline-card-top">
                <span :class="['kind-chip', item.barClass === 'overdue' ? item.kind : item.barClass]">{{ item.badgeText }}</span>
                <span class="timeline-summary-text">{{ item.summary }}</span>
              </div>
              <strong>{{ item.title }}</strong>
              <small>{{ item.sourceLabel }}</small>
              <button v-if="item.sourceType === 'schedule' && item.status === 'pending'" type="button" class="home-timeline-complete" :disabled="completingScheduleId !== null" title="完成日程" aria-label="完成日程" @click.stop="completeSchedule(item)">
                <LoaderCircle v-if="completingScheduleId === item.id" class="spinning" :size="15" />
                <Check v-else :size="15" />
              </button>
            </div>
          </article>
        </div>
        <div v-if="timelineEntries.length <= 1" class="compact-empty"><Clock3 :size="22" /><span>时间轴还是空的</span></div>
      </aside>
    </div>
  </section>
</template>
