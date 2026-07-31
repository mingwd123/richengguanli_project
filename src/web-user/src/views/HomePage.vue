<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, countdown, urgency, groupByTaskState } from '../utils/helpers'
import type { TimelineItem } from '../types'

const store = useAppStore()
const router = useRouter()
const nowTs = ref(Date.now())
let nowTimer = 0
const collapsedModules = ref<string[]>([])
const collapsedTeamModules = ref<string[]>([])

const TIMELINE_LIMIT = 12

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
  store.schedules.filter(schedule => !['completed', 'cancelled'].includes(schedule.status)),
  schedule => schedule.groupName || '未分组'
))

const teamTaskModules = computed(() => groupByTaskState(
  store.myTasks.filter(task => !['completed', 'cancelled', 'all_rejected'].includes(task.status) && !['completed', 'rejected'].includes(task.assignStatus)),
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
  const isDuration = item.kind === 'duration_task' && item.startTime && item.endTime
  const startTs = new Date(isDuration ? item.startTime! : item.sortAt).getTime()
  const endTs = isDuration ? new Date(item.endTime!).getTime() : startTs
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
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date(value))
}

function formatClock(value: number | string) {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

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
</script>

<template>
  <section class="home-layout">
    <div class="stats-row">
      <article class="stat-card">
        <span>待办日程</span>
        <strong>{{ store.pendingScheduleCount }}</strong>
      </article>
      <article class="stat-card">
        <span>团队任务</span>
        <strong>{{ store.activeTaskCount }}</strong>
      </article>
      <article class="stat-card">
        <span>未读通知</span>
        <strong>{{ store.today.unreadNotificationCount }}</strong>
      </article>
    </div>

    <div class="ai-plan-section" v-if="aiPlan">
      <div class="ai-plan-header"><span>🤖 AI 今日计划</span><button class="plain-button" @click="loadAiPlan" :disabled="aiLoadingPlan" style="font-size:12px">{{ aiLoadingPlan ? '生成中...' : '刷新' }}</button></div>
      <p class="ai-plan-content">{{ aiPlan }}</p>
    </div>
    <div class="ai-plan-section" v-else style="cursor:pointer;background:#f0f9ff;border:1px dashed #93c5fd" @click="loadAiPlan">
      <p class="muted" style="text-align:center;padding:12px">{{ aiLoadingPlan ? 'AI 正在思考今天的计划...' : '🤖 点击生成 AI 今日计划建议' }}</p>
    </div>

    <div class="home-content">
      <section class="group-panel">
        <div class="section-head">
          <h2>日程模块</h2>
        </div>
        <section v-for="module in scheduleModules" :key="module.name" class="home-module">
          <div class="module-title" style="cursor:pointer" @click="toggleModule(module.name)">
            <button class="plain-button"><span>{{ collapsedModules.includes(module.name) ? '▸' : '▾' }} {{ module.name }}</span></button>
            <span>{{ module.items.filter(item => item.status === 'pending').length }} 项待办</span>
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
              <span :class="['tag', urgency(schedule.deadlineTime || schedule.endTime || schedule.startTime)]">{{ countdown(schedule.deadlineTime || schedule.endTime || schedule.startTime) }}</span>
            </article>
            <p v-if="module.items.length > 3" class="muted module-more">另有 {{ module.items.length - 3 }} 项日程，请前往日程页查看</p>
          </template>
        </section>
        <p v-if="scheduleModules.length === 0" class="muted" style="padding: 20px; text-align: center;">暂无日程</p>

        <div class="section-head" style="margin-top:18px">
          <h2>团队任务模块</h2>
        </div>
        <section v-for="module in teamTaskModules" :key="`team-${module.name}`" class="home-module">
          <div class="module-title" style="cursor:pointer" @click="toggleTeamModule(module.name)">
            <button class="plain-button"><span>{{ collapsedTeamModules.includes(module.name) ? '▸' : '▾' }} {{ module.name }}</span></button>
            <span>{{ module.items.length }} 项待处理</span>
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
              <span :class="['tag', urgency(task.deadlineTime || task.startTime)]">{{ countdown(task.deadlineTime || task.startTime) }}</span>
            </article>
            <p v-if="module.items.length > 3" class="muted module-more">另有 {{ module.items.length - 3 }} 项团队任务，请前往任务页查看</p>
          </template>
        </section>
        <p v-if="teamTaskModules.length === 0" class="muted" style="padding: 16px 20px; text-align: center;">暂无团队任务</p>
      </section>

      <aside class="timeline-panel">
        <div class="timeline-head">
          <div>
            <h2>时间轴</h2>
            <p>{{ store.timelineStats.today }} 今天 / {{ store.timelineStats.overdue }} 逾期</p>
          </div>
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
        <p v-if="timelineEntries.length <= 1" class="muted" style="padding: 30px; text-align: center;">暂无时间轴数据</p>
      </aside>
    </div>
  </section>
</template>
