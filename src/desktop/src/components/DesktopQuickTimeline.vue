<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  CalendarClock,
  Check,
  ChevronRight,
  CircleDot,
  Clock3,
  AlertTriangle,
  BatteryMedium,
  Edit3,
  Flag,
  Layers3,
  ListTodo,
  PanelTopOpen,
  Plus,
  RefreshCw,
  RotateCcw,
  UserRound,
  XCircle,
} from 'lucide-vue-next'
import { useAppStore } from '@web/stores/app'
import type {
  MyTask,
  PageResult,
  Schedule,
  ScheduleForm,
  ScheduleListResult,
  ScheduleViewMode,
  SectionSummary,
  TaskForm,
  TaskGroup,
  TeamMember,
  TeamTask,
  TimeType,
  TimelinePresentationStatus,
  TimelineTimeRange,
} from '@web/types'
import { countdown, dateKeyInTimezone, formatTime, getDisplayTimezone, progressCompletionActionLabel, statusLabel, toApiTimePayload, toSchedulePayload } from '@web/utils/helpers'
// 进度面板与网页端共用，避免桌面端再实现一套每日进度规则。
import ScheduleProgressPanel from '@web/components/ScheduleProgressPanel.vue'
import {
  buildTimelineStats,
  timelineOccursOnDate,
  timelinePresentationStatus,
  timelineTimeRange,
} from '@web/utils/timeline'
import { useDesktopShell } from '../stores/desktopShell'
import DesktopQuickScheduleEditor from './DesktopQuickScheduleEditor.vue'
import DesktopQuickFatigueSurvey from './DesktopQuickFatigueSurvey.vue'
import DesktopQuickTeamBreakdown, { type QuickTeamBreakdownTask } from './DesktopQuickTeamBreakdown.vue'
import DesktopQuickTeamTaskCreator from './DesktopQuickTeamTaskCreator.vue'
import DesktopQuickTaskEditor from './DesktopQuickTaskEditor.vue'
import type { QuickTaskEditForm } from './desktopQuickTypes'
import { isoToZonedDatetimeLocal, zonedDatetimeLocalToIso } from '../utils/timezone'
import { inferAiGroupId, normalizeAiDateTime, normalizeAiScheduleDraft, resolveAiTimeType } from '../utils/aiSchedule'
import {
  defaultQuickScrollPositions,
  entriesForQuickSource,
  quickPreferenceKeys,
  quickScheduleQuery,
  scheduleDataRevision,
  shouldAcceptScheduleRevision,
  shouldDeferQuickTarget,
  type QuickSource,
  type QuickScheduleStatus,
} from '../utils/desktopQuickScheduleViews'

type SourceType = 'schedule' | 'team_task'
type RawTimelineItem = Schedule | MyTask
type QuickRow = { type: 'item'; entry: QuickEntry } | { type: 'now' }
type QuickView =
  | { kind: 'timeline'; scrollTop: number }
  | { kind: 'create'; scrollTop: number }
  | { kind: 'team-breakdown'; scrollTop: number }
  | { kind: 'schedule-detail'; id: number; scrollTop: number }
  | { kind: 'schedule-edit'; id: number; scrollTop: number }
  | { kind: 'task-detail'; id: number; scrollTop: number }
  | { kind: 'task-edit'; id: number; scrollTop: number }
  | { kind: 'fatigue-survey'; localDate: string; scrollTop: number }

type QuickEntry = {
  key: string
  sourceType: SourceType
  raw: RawTimelineItem
  range: TimelineTimeRange
  presentation: TimelinePresentationStatus
  sourceLabel: string
}

type QuickTeamBreakdownBase = Pick<TaskForm, 'teamId' | 'groupId' | 'startTime' | 'remindAt'>
const BREAKDOWN_EXIT_MESSAGE = 'AI 子任务草稿尚未创建，切换到完整工作台会丢失这些草稿，仍要继续吗？'
type QuickSection = SectionSummary & { items: QuickEntry[] }

function quickModeRecord<T>(create: () => T): Record<ScheduleViewMode, T> {
  return {
    time: create(),
    group: create(),
    urgency: create(),
    fatigue: create(),
  }
}

const store = useAppStore()
const shell = useDesktopShell()
const router = useRouter()
const workspaceRoot = ref<HTMLElement | null>(null)
const viewStack = ref<QuickView[]>([{ kind: 'timeline', scrollTop: 0 }])
const now = ref(Date.now())
const detailLoading = ref(false)
const formBusy = ref(false)
const actionBusy = ref(false)
const pendingCompleteTask = ref<{ id: number; title: string } | null>(null)
const completingFatigueLevel = ref<number | null>(null)
const scheduleEditScope = ref<'occurrence' | 'series'>('occurrence')
const fatigueTrackingOn = computed(() => Boolean(store.fatigueProfile?.fatigueTrackingEnabled && store.fatigueProfile?.featureEnabled))
const completionFatigueLabels = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']
const aiParsing = ref(false)
const aiParseError = ref('')
const aiParseNotice = ref('')
const aiBreaking = ref(false)
const aiOptimizingDescription = ref(false)
const aiBreakdownSaving = ref(false)
const teamAiError = ref('')
const teamAiNotice = ref('')
const aiBreakdownTasks = ref<QuickTeamBreakdownTask[]>([])
const aiBreakdownBase = ref<QuickTeamBreakdownBase | null>(null)
const quickSource = ref<QuickSource>('personal')
const quickViewMode = ref<ScheduleViewMode>('time')
const quickScheduleStatus = ref<QuickScheduleStatus>('pending')
const quickScheduleResults = ref<Record<ScheduleViewMode, ScheduleListResult | null>>(quickModeRecord(() => null))
const quickPersonalLoading = ref<Record<ScheduleViewMode, boolean>>(quickModeRecord(() => false))
const quickPersonalErrors = ref<Record<ScheduleViewMode, string>>(quickModeRecord(() => ''))
const quickAcceptedScheduleRevision = ref<number | null>(null)
const quickTasks = ref<MyTask[]>([])
const quickTeamLoading = ref(false)
const quickTeamLoaded = ref(false)
const quickTeamError = ref('')
const quickScheduleResult = computed(() => quickScheduleResults.value[quickViewMode.value])
const quickSchedules = computed(() => quickScheduleResult.value?.list || [])
const quickDataLoading = computed(() => quickSource.value === 'personal'
  ? quickPersonalLoading.value[quickViewMode.value]
  : quickTeamLoading.value)
const quickDataLoaded = computed(() => quickSource.value === 'personal'
  ? quickScheduleResult.value !== null
  : quickTeamLoaded.value)
const quickDataError = computed(() => quickSource.value === 'personal'
  ? quickPersonalErrors.value[quickViewMode.value]
  : quickTeamError.value)
const quickRefreshLoading = computed(() => quickTeamLoading.value
  || Object.values(quickPersonalLoading.value).some(Boolean))
const quickScrollPositions = ref<Record<ScheduleViewMode, number>>(defaultQuickScrollPositions())
const scheduleDetail = ref<Schedule | null>(null)
const quickProgressPanel = ref<{ openSubmit: (target?: number) => void; openBackfill: () => void } | null>(null)
const teamTaskDetail = ref<TeamTask | null>(null)
const teamTaskGroups = ref<TaskGroup[]>([])
const teamCreateGroups = ref<TaskGroup[]>([])
const teamCreateMembers = ref<TeamMember[]>([])
const teamCreateContextLoading = ref(false)
const loadedTeamCreateId = ref('')
const createKind = ref<'schedule' | 'team_task'>('schedule')
const initialScheduleReminder = ref('')
const initialTaskReminder = ref('')
let createFormInitialized = false
let teamCreateContextRequest = 0
let detailRequest = 0
const quickPersonalRequests = quickModeRecord(() => 0)
let quickTeamRequest = 0
let aiParseRequest = 0
let aiBreakdownRequest = 0
let aiOptimizeRequest = 0
let timer: number | undefined

const activeView = computed(() => viewStack.value[viewStack.value.length - 1])
const timezone = computed(() => store.profile?.timezone || getDisplayTimezone())
const createForm = ref<ScheduleForm>(newScheduleForm())
const teamCreateForm = ref<TaskForm>(newTeamTaskForm())
const scheduleEditForm = ref<ScheduleForm>(newScheduleForm())
const taskEditForm = ref<QuickTaskEditForm>(newTaskEditForm())
const todayKey = computed(() => store.fatigueDaily?.localDate
  || store.fatigueSurveyToday?.localDate
  || dateKeyInTimezone(now.value, timezone.value))
const todayLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'UTC',
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}).format(new Date(`${todayKey.value}T12:00:00Z`)))

const quickViewOptions = [
  { value: 'time' as ScheduleViewMode, label: '时间', icon: Clock3 },
  { value: 'group' as ScheduleViewMode, label: '分组', icon: Layers3 },
  { value: 'urgency' as ScheduleViewMode, label: '紧急', icon: AlertTriangle },
  { value: 'fatigue' as ScheduleViewMode, label: '疲劳', icon: BatteryMedium },
]

const quickStatusOptions = [
  { value: 'pending' as QuickScheduleStatus, label: '待处理' },
  { value: 'completed' as QuickScheduleStatus, label: '已完成' },
  { value: 'cancelled' as QuickScheduleStatus, label: '已取消' },
]

const nestedViewTitle = computed(() => {
  if (activeView.value.kind === 'create') return '新建任务'
  if (activeView.value.kind === 'team-breakdown') return '确认 AI 子任务'
  if (activeView.value.kind === 'schedule-detail') return scheduleDetail.value?.title || '日程详情'
  if (activeView.value.kind === 'schedule-edit') return '编辑个人日程'
  if (activeView.value.kind === 'task-detail') return teamTaskDetail.value?.title || '团队任务详情'
  if (activeView.value.kind === 'task-edit') return '编辑团队任务'
  if (activeView.value.kind === 'fatigue-survey') return '个人日程疲劳调查'
  return '快捷时间轴'
})

const nestedViewKicker = computed(() => {
  if (activeView.value.kind === 'create') return createKind.value === 'schedule' ? '个人日程' : '团队任务'
  if (activeView.value.kind === 'team-breakdown') return '团队任务 · AI 拆解'
  if (activeView.value.kind.startsWith('schedule')) return '个人日程'
  if (activeView.value.kind.startsWith('task')) return '团队任务'
  if (activeView.value.kind === 'fatigue-survey') return activeView.value.localDate
  return '今天'
})

function itemKey(sourceType: SourceType, item: RawTimelineItem) {
  return `${sourceType}-${item.id}`
}

function sourceItem(item: RawTimelineItem, sourceType: SourceType) {
  return { ...item, sourceType }
}

const personalEntries = computed<QuickEntry[]>(() => quickSchedules.value.map(raw => {
  const sourceType: SourceType = 'schedule'
  const item = sourceItem(raw, sourceType)
  const presentation = raw.status === 'pending' && raw.isOverdue
    ? 'overdue'
    : timelinePresentationStatus(item, now.value)
  return {
    key: itemKey(sourceType, raw),
    sourceType,
    raw,
    range: timelineTimeRange(item),
    presentation,
    sourceLabel: raw.groupName || '个人日程',
  }
}))

const teamEntries = computed<QuickEntry[]>(() => quickTasks.value.map(raw => {
  const sourceType: SourceType = 'team_task'
  const item = sourceItem(raw, sourceType)
  return {
    key: itemKey(sourceType, raw),
    sourceType,
    raw,
    range: timelineTimeRange(item),
    presentation: timelinePresentationStatus(item, now.value),
    sourceLabel: raw.teamName || '团队任务',
  }
}))

const entries = computed<QuickEntry[]>(() => entriesForQuickSource(quickSource.value, personalEntries.value, teamEntries.value))

const personalSections = computed<QuickSection[]>(() => {
  const loaded = new Map<string, QuickEntry[]>()
  for (const entry of personalEntries.value) {
    const schedule = entry.raw as Schedule
    const key = schedule.sectionKey || `${quickViewMode.value}:${schedule.sectionLabel || schedule.groupName || '未分组'}`
    const items = loaded.get(key) || []
    items.push(entry)
    loaded.set(key, items)
  }
  const summaries = quickScheduleResult.value?.sectionSummaries || []
  if (!summaries.length) {
    return [...loaded.entries()].map(([key, items]) => ({
      key,
      label: (items[0]?.raw as Schedule)?.sectionLabel || (items[0]?.raw as Schedule)?.groupName || '未分组',
      total: items.length,
      pendingCount: items.length,
      completedCount: 0,
      plannedLoad: items.reduce((sum, item) => sum + Number((item.raw as Schedule).fatigueWeight || 3), 0),
      completedLoad: 0,
      items,
    }))
  }
  return summaries.map(summary => ({ ...summary, items: loaded.get(summary.key) || [] }))
})

const openEntries = computed(() => quickScheduleStatus.value === 'pending'
  ? entries.value.filter(entry => !['completed', 'cancelled', 'rejected'].includes(entry.presentation))
  : entries.value)

const overdueEntries = computed(() => openEntries.value
  .filter(entry => entry.presentation === 'overdue')
  .sort((left, right) => timeValue(left.range.endAt) - timeValue(right.range.endAt)))

const timelineEntries = computed(() => openEntries.value
  .filter(entry => entry.presentation !== 'overdue')
  .filter(entry => entry.range.sortAt && entry.presentation !== 'unscheduled')
  .filter(entry => timelineOccursOnDate(sourceItem(entry.raw, entry.sourceType), todayKey.value, timezone.value))
  .sort((left, right) => timeValue(left.range.sortAt) - timeValue(right.range.sortAt)))

const visibleTimelineEntries = computed(() => timelineEntries.value.slice(0, 8))

const timelineRows = computed<QuickRow[]>(() => {
  const rows: QuickRow[] = visibleTimelineEntries.value.map(entry => ({ type: 'item', entry }))
  const firstUpcoming = rows.findIndex(row => row.type === 'item' && timeValue(row.entry.range.sortAt) >= now.value)
  rows.splice(firstUpcoming < 0 ? rows.length : firstUpcoming, 0, { type: 'now' })
  return rows
})

const unscheduledEntries = computed(() => openEntries.value
  .filter(entry => entry.presentation === 'unscheduled')
  .sort((left, right) => left.raw.title.localeCompare(right.raw.title, 'zh-CN')))

const stats = computed(() => buildTimelineStats(
  entries.value.map(entry => sourceItem(entry.raw, entry.sourceType)),
  { now: now.value, timezone: timezone.value },
))

const quickHeaderCount = computed(() => quickSource.value === 'personal'
  ? Number(quickScheduleResult.value?.total || 0)
  : stats.value.today)

const fatigueStateLabel = computed(() => {
  const labels: Record<string, string> = {
    comfortable: '舒适',
    full: '较满',
    tired: '较疲劳',
    high: '高负荷',
    overloaded: '可能过载',
  }
  return labels[store.fatigueDaily?.level || ''] || '暂无估算'
})

const quickLoadPercent = computed(() => {
  const planned = Number(store.fatigueDaily?.plannedLoad || 0)
  const capacity = Number(store.fatigueDaily?.capacity75 || store.fatigueProfile?.capacity75 || 0)
  return capacity > 0 ? Math.min(100, Math.round(planned / capacity * 100)) : 0
})

const quickHistoryLoad = computed(() => (quickScheduleResult.value?.sectionSummaries || [])
  .reduce((sum, section) => sum + Number(section.completedLoad || 0), 0))

const fatigueAlertText = computed(() => {
  const level = store.fatigueDaily?.level
  if (level === 'overloaded') return '预计负荷已超过个人承受上限，建议调整今天的个人日程'
  if (level === 'high') return '预计负荷接近上限，可以优先处理高紧急度日程'
  if (level === 'tired') return '今天的个人日程负荷偏高，记得预留休息时间'
  return ''
})

const fatigueSurveyStatus = computed(() => {
  const surveyState = store.fatigueSurveyToday
  if (surveyState?.pending) return '待填写'
  if (surveyState?.survey && 'score' in surveyState.survey) return '已记录'
  if (surveyState?.profile?.surveySkippedDate === surveyState.localDate) return '已跳过'
  if (surveyState?.profile?.surveySnoozedUntil && new Date(surveyState.profile.surveySnoozedUntil).getTime() > now.value) return '稍后提醒'
  return '暂无待办'
})

const schedulePresentation = computed(() => scheduleDetail.value
  ? timelinePresentationStatus(sourceItem(scheduleDetail.value, 'schedule'), now.value)
  : 'unscheduled')

const scheduleIsRecurring = computed(() => Boolean(scheduleDetail.value?.seriesId || scheduleDetail.value?.rrule))

const myTaskAssignment = computed(() => {
  if (!teamTaskDetail.value || !store.profile) return null
  return teamTaskDetail.value.assignees?.find(assignee => assignee.isCurrent && assignee.userId === store.profile?.id) || null
})

const canManageTeamTask = computed(() => {
  if (!teamTaskDetail.value || !store.profile) return false
  if (teamTaskDetail.value.canManage || teamTaskDetail.value.creatorId === store.profile.id) return true
  const team = store.teams.find(item => item.id === teamTaskDetail.value?.teamId)
  return team?.myRole === 'owner' || team?.myRole === 'admin'
})

const canAdminTeamTask = computed(() => {
  if (!teamTaskDetail.value) return false
  const team = store.teams.find(item => item.id === teamTaskDetail.value?.teamId)
  return team?.myRole === 'owner' || team?.myRole === 'admin'
})

const hasTeamTaskVacancies = computed(() => Number(teamTaskDetail.value?.unassignedCount || 0) > 0)

function isActionableTeamTask(task: Pick<MyTask, 'status'>) {
  return ['active', 'unassigned'].includes(task.status)
}

function roundedFuture(minutes: number) {
  const date = new Date(Date.now() + minutes * 60_000)
  date.setMinutes(Math.ceil(date.getMinutes() / 15) * 15, 0, 0)
  return isoToZonedDatetimeLocal(date.toISOString(), timezone.value)
}

function newScheduleForm(): ScheduleForm {
  return {
    title: '',
    description: '',
    groupId: '',
    groupName: '',
    timeType: 'point_event',
    startTime: roundedFuture(30),
    endTime: roundedFuture(90),
    deadlineTime: roundedFuture(60),
    remindAt: '',
    urgencyLevel: 3,
    fatigueLevel: 3,
    rrule: '',
    excludedDates: [],
    progressTrackingEnabled: false,
  }
}

function newTaskEditForm(): QuickTaskEditForm {
  return { title: '', description: '', groupId: '', startTime: '', deadlineTime: '', remindAt: '' }
}

function newTeamTaskForm(teamId = ''): TaskForm {
  return { teamId, groupId: '', title: '', description: '', startTime: '', deadlineTime: '', remindAt: '', assigneeUserIds: [] }
}

function schedulePayload(form: ScheduleForm) {
  const payload = toSchedulePayload(form)
  for (const key of ['startTime', 'endTime', 'deadlineTime', 'remindAt'] as const) {
    if (payload[key] && form[key]) payload[key] = zonedDatetimeLocalToIso(form[key], timezone.value)
  }
  return payload
}

function taskPayload(form: TaskForm | QuickTaskEditForm) {
  const payload = toApiTimePayload({ ...form })
  for (const key of ['startTime', 'deadlineTime', 'remindAt'] as const) {
    if (form[key]) payload[key] = zonedDatetimeLocalToIso(form[key], timezone.value)
  }
  return payload
}

function timeValue(value: string) {
  const timestamp = new Date(value).getTime()
  return Number.isNaN(timestamp) ? Number.MAX_SAFE_INTEGER : timestamp
}

function loadQuickPreferences() {
  const userId = store.profile?.id
  if (!userId) return
  const keys = quickPreferenceKeys(userId)
  const mode = localStorage.getItem(keys.view)
  if (['time', 'group', 'urgency', 'fatigue'].includes(mode || '')) quickViewMode.value = mode as ScheduleViewMode
  const source = localStorage.getItem(keys.source)
  if (source === 'personal' || source === 'team') quickSource.value = source
  try {
    const saved = JSON.parse(localStorage.getItem(keys.scroll) || '{}')
    quickScrollPositions.value = {
      time: Math.max(0, Number(saved.time) || 0),
      group: Math.max(0, Number(saved.group) || 0),
      urgency: Math.max(0, Number(saved.urgency) || 0),
      fatigue: Math.max(0, Number(saved.fatigue) || 0),
    }
  } catch {
    quickScrollPositions.value = defaultQuickScrollPositions()
  }
}

function persistQuickPreferences() {
  const userId = store.profile?.id
  if (!userId) return
  const keys = quickPreferenceKeys(userId)
  localStorage.setItem(keys.view, quickViewMode.value)
  localStorage.setItem(keys.source, quickSource.value)
  localStorage.setItem(keys.scroll, JSON.stringify(quickScrollPositions.value))
}

async function loadQuickPersonalSchedules(mode: ScheduleViewMode, notifyOnError: boolean) {
  const userId = store.profile?.id
  if (!userId) return
  const requestId = ++quickPersonalRequests[mode]
  quickPersonalLoading.value[mode] = true
  quickPersonalErrors.value[mode] = ''
  try {
    const scheduleQuery = quickScheduleQuery(mode, quickScheduleStatus.value)
    const schedules = await store.request<ScheduleListResult>(`/schedules?${scheduleQuery.toString()}`)
    if (requestId !== quickPersonalRequests[mode] || userId !== store.profile?.id) return
    const revision = scheduleDataRevision(schedules)
    if (!shouldAcceptScheduleRevision(quickAcceptedScheduleRevision.value, revision)) {
      quickPersonalErrors.value[mode] = '个人日程响应版本过旧，已保留最近一次有效结果'
      if (notifyOnError) store.notify(quickPersonalErrors.value[mode])
      return
    }
    quickScheduleResults.value[mode] = schedules
    if (revision !== null) quickAcceptedScheduleRevision.value = revision
  } catch (error: any) {
    if (requestId === quickPersonalRequests[mode] && userId === store.profile?.id) {
      quickPersonalErrors.value[mode] = error.message || '加载个人日程失败'
      if (notifyOnError) store.notify(quickPersonalErrors.value[mode])
    }
  } finally {
    if (requestId === quickPersonalRequests[mode]) quickPersonalLoading.value[mode] = false
  }
}

async function loadQuickTeamTasks(notifyOnError: boolean) {
  const userId = store.profile?.id
  if (!userId) return
  const requestId = ++quickTeamRequest
  quickTeamLoading.value = true
  quickTeamError.value = ''
  try {
    const taskQuery = (status: string) => new URLSearchParams({ page: '1', size: '40', status, sort: 'time_asc' })
    const [pendingTasks, acceptedTasks] = await Promise.all([
      store.request<PageResult<MyTask>>(`/team-tasks/my?${taskQuery('pending').toString()}`),
      store.request<PageResult<MyTask>>(`/team-tasks/my?${taskQuery('accepted').toString()}`),
    ])
    if (requestId !== quickTeamRequest || userId !== store.profile?.id) return
    const tasks = new Map<number, MyTask>()
    for (const task of [...(pendingTasks.list || []), ...(acceptedTasks.list || [])]) {
      if (!isActionableTeamTask(task) || !['pending', 'accepted'].includes(task.assignStatus || '')) continue
      tasks.set(task.id, task)
    }
    quickTasks.value = [...tasks.values()]
    quickTeamLoaded.value = true
  } catch (error: any) {
    if (requestId === quickTeamRequest && userId === store.profile?.id) {
      quickTeamError.value = error.message || '加载团队任务失败'
      if (notifyOnError) store.notify(quickTeamError.value)
    }
  } finally {
    if (requestId === quickTeamRequest) quickTeamLoading.value = false
  }
}

async function loadQuickTimelineData(
  notifyOnError = true,
  mode = quickViewMode.value,
  source?: QuickSource,
) {
  const selectedSource = source || quickSource.value
  const jobs: Promise<void>[] = []
  if (!source || source === 'personal') {
    jobs.push(loadQuickPersonalSchedules(mode, notifyOnError && selectedSource === 'personal'))
  }
  if (!source || source === 'team') {
    jobs.push(loadQuickTeamTasks(notifyOnError && selectedSource === 'team'))
  }
  await Promise.all(jobs)
}

async function refreshQuickWorkspace() {
  await Promise.all([store.loadAll(), loadQuickTimelineData()])
}

async function selectQuickSource(source: QuickSource) {
  rememberTimelineScroll()
  quickSource.value = source
  persistQuickPreferences()
  if (source === 'personal') await restoreScroll(quickScrollPositions.value[quickViewMode.value])
  if (!quickDataLoaded.value && !quickDataLoading.value) {
    await loadQuickTimelineData(true, quickViewMode.value, source)
  }
}

async function selectQuickView(mode: ScheduleViewMode) {
  if (quickViewMode.value === mode) return
  rememberTimelineScroll()
  quickViewMode.value = mode
  persistQuickPreferences()
  await loadQuickTimelineData(true, mode, 'personal')
  await restoreScroll(quickScrollPositions.value[mode])
}

async function selectQuickScheduleStatus(status: QuickScheduleStatus) {
  if (quickScheduleStatus.value === status) return
  quickScheduleStatus.value = status
  quickScheduleResults.value[quickViewMode.value] = null
  await loadQuickTimelineData(true, quickViewMode.value, 'personal')
}

async function openFatigueSurvey() {
  const localDate = store.fatigueSurveyToday?.localDate || store.fatigueDaily?.localDate || todayKey.value
  await pushView({ kind: 'fatigue-survey', localDate, scrollTop: 0 })
}

function quickViewHasUnsavedWork() {
  return shouldDeferQuickTarget(activeView.value.kind)
}

async function processPendingQuickTarget() {
  const target = shell.pendingQuickTarget.value
  if (!target || quickViewHasUnsavedWork()) return
  shell.consumeQuickTarget(target)
  if (target.kind === 'timeline') {
    await returnToTimeline()
    return
  }
  const localDate = target.localDate || store.fatigueSurveyToday?.localDate || store.fatigueDaily?.localDate || todayKey.value
  if (activeView.value.kind === 'fatigue-survey' && activeView.value.localDate === localDate) return
  await pushView({ kind: 'fatigue-survey', localDate, scrollTop: 0 })
}

async function openFullScheduleView() {
  await router.push({ path: '/schedules', query: { view: quickViewMode.value } })
  await shell.setQuickTimeline(false)
}

async function openCreateForSource() {
  if (quickSource.value === 'personal') {
    await openCreateSchedule()
    return
  }
  await router.push('/tasks')
  await shell.setQuickTimeline(false)
}

function formatClock(value: string) {
  const timestamp = timeValue(value)
  if (timestamp === Number.MAX_SAFE_INTEGER) return '待定'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone.value,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(timestamp)
}

function formatTimelineTime(entry: QuickEntry) {
  if (!entry.range.isDuration) return formatClock(entry.range.sortAt)
  const startsToday = dateKeyInTimezone(entry.range.startAt, timezone.value) === todayKey.value
  const endsToday = dateKeyInTimezone(entry.range.endAt, timezone.value) === todayKey.value
  if (startsToday && endsToday) return `${formatClock(entry.range.startAt)} - ${formatClock(entry.range.endAt)}`
  if (!startsToday && endsToday) return `持续中 - ${formatClock(entry.range.endAt)}`
  if (startsToday) return `${formatClock(entry.range.startAt)} - 明日`
  return '持续中'
}

function kindLabel(kind: TimeType) {
  if (kind === 'point_event') return '瞬时日程'
  if (kind === 'duration_task') return '持续任务'
  return '截止任务'
}

function scheduleLevelLabel(level: number, kind: 'urgency' | 'fatigue') {
  const labels = kind === 'urgency'
    ? ['不紧急', '较低', '普通', '紧急', '非常紧急']
    : ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']
  return labels[Math.max(0, Math.min(4, Number(level || 3) - 1))]
}

function scheduleTime(schedule: Schedule) {
  return schedule.effectiveTime || schedule.deadlineTime || schedule.endTime || schedule.startTime || ''
}

function displayScheduleTime(schedule: Schedule) {
  return schedule.status === 'completed' && schedule.completedAt ? schedule.completedAt : scheduleTime(schedule)
}

function displayFatigueLevel(schedule: Schedule) {
  return schedule.status === 'completed' && schedule.completedFatigueLevel ? schedule.completedFatigueLevel : schedule.fatigueLevel
}

function displayFatigueWeight(schedule: Schedule) {
  // 进度任务的负荷取每日记录累计值，不回退到整项计划疲劳权重。
  if (schedule.progressTrackingEnabled) return Number(schedule.progressCompletedLoad ?? 0)
  return schedule.status === 'completed' && schedule.completedFatigueWeight != null ? schedule.completedFatigueWeight : schedule.fatigueWeight
}

function presentationLabel(presentation: TimelinePresentationStatus) {
  const labels: Record<TimelinePresentationStatus, string> = {
    upcoming: '待处理',
    active: '进行中',
    past: '已过',
    overdue: '已逾期',
    completed: '已完成',
    cancelled: '已取消',
    rejected: '已拒绝',
    unscheduled: '待安排',
  }
  return labels[presentation]
}

function actionLabel(entry: QuickEntry) {
  if (entry.sourceType === 'schedule') {
    const schedule = entry.raw as Schedule
    return schedule.status === 'pending' ? '完成' : '恢复'
  }
  const task = entry.raw as MyTask
  if (!isActionableTeamTask(task)) return ''
  if (task.assignStatus === 'pending') return '接受'
  if (task.assignStatus === 'accepted') return '完成'
  return ''
}

function actionIcon(entry: QuickEntry) {
  if (entry.sourceType === 'schedule' && (entry.raw as Schedule).status !== 'pending') return RotateCcw
  return Check
}

function sectionLoadText(section: QuickSection) {
  if (quickScheduleStatus.value === 'completed') return `完成负荷 ${Number(section.completedLoad || 0)} 点`
  if (quickScheduleStatus.value === 'pending') return `计划负荷 ${Number(section.plannedLoad || 0)} 点`
  return `${section.total} 项`
}

function canAct(entry: QuickEntry) {
  return Boolean(actionLabel(entry))
}

function rememberTimelineScroll() {
  if (activeView.value.kind !== 'timeline' || quickSource.value !== 'personal' || !workspaceRoot.value) return
  quickScrollPositions.value[quickViewMode.value] = workspaceRoot.value.scrollTop
}

function handleWorkspaceScroll() {
  rememberTimelineScroll()
}

function rememberScroll() {
  if (workspaceRoot.value) activeView.value.scrollTop = workspaceRoot.value.scrollTop
}

async function restoreScroll(value: number) {
  await nextTick()
  if (workspaceRoot.value) workspaceRoot.value.scrollTop = value
}

async function pushView(view: QuickView) {
  rememberScroll()
  viewStack.value.push(view)
  await restoreScroll(view.scrollTop)
}

async function goBack() {
  if (viewStack.value.length <= 1) return
  if (activeView.value.kind === 'team-breakdown' && aiBreakdownSaving.value) return
  if (activeView.value.kind === 'team-breakdown') shell.setQuickTimelineExitGuard('none')
  if (activeView.value.kind === 'create') invalidateAiRequests()
  viewStack.value.pop()
  await restoreScroll(activeView.value.scrollTop)
}

async function returnToTimeline() {
  invalidateAiRequests()
  if (viewStack.value.length === 1) return
  viewStack.value = [viewStack.value[0]]
  await restoreScroll(activeView.value.scrollTop)
}

function ensureCreateFormDefaults() {
  if (!createForm.value.groupId && store.taskGroups[0]) createForm.value.groupId = String(store.taskGroups[0].id)
}

function clearAiParseState() {
  aiParseError.value = ''
  aiParseNotice.value = ''
}

function clearTeamAiState() {
  teamAiError.value = ''
  teamAiNotice.value = ''
}

function invalidateAiRequests() {
  aiParseRequest += 1
  aiBreakdownRequest += 1
  aiOptimizeRequest += 1
  aiParsing.value = false
  aiBreaking.value = false
  aiOptimizingDescription.value = false
}

async function openCreateSchedule() {
  if (!createFormInitialized) {
    createForm.value = newScheduleForm()
    createFormInitialized = true
  }
  ensureCreateFormDefaults()
  clearAiParseState()
  clearTeamAiState()
  if (!teamCreateForm.value.teamId && store.teams[0]) teamCreateForm.value.teamId = String(store.teams[0].id)
  await pushView({ kind: 'create', scrollTop: 0 })
}

async function setCreateKind(kind: 'schedule' | 'team_task') {
  if (aiParsing.value || aiBreaking.value || aiOptimizingDescription.value) return
  invalidateAiRequests()
  createKind.value = kind
  clearAiParseState()
  clearTeamAiState()
  if (kind === 'schedule') {
    ensureCreateFormDefaults()
    return
  }
  if (!teamCreateForm.value.teamId && store.teams[0]) teamCreateForm.value.teamId = String(store.teams[0].id)
  if (teamCreateForm.value.teamId && loadedTeamCreateId.value !== teamCreateForm.value.teamId) {
    await loadTeamCreateContext(teamCreateForm.value.teamId)
  }
}

async function parseCreateWithAi() {
  const sourceText = createForm.value.title.trim()
  if (!sourceText) {
    store.notify('请先在标题中输入要解析的内容')
    return
  }

  const requestId = ++aiParseRequest
  const formSnapshot = JSON.stringify(createForm.value)
  aiParsing.value = true
  clearAiParseState()
  try {
    const result = await store.aiRequest('/schedules/parse', { text: sourceText })
    if (requestId !== aiParseRequest || activeView.value.kind !== 'create' || createKind.value !== 'schedule') return
    if (JSON.stringify(createForm.value) !== formSnapshot) {
      aiParseNotice.value = '表单已被修改，本次 AI 结果未应用，请重新解析'
      return
    }
    const draft = normalizeAiScheduleDraft(result.draft)
    const current = createForm.value
    const timeType = resolveAiTimeType(draft, current.timeType)
    const groupId = inferAiGroupId(store.taskGroups, draft, sourceText, current.groupId)
    createForm.value = {
      ...current,
      title: draft.title || current.title,
      description: draft.description || current.description,
      groupId,
      groupName: store.taskGroups.find(group => String(group.id) === groupId)?.name || current.groupName,
      timeType,
      startTime: normalizeAiDateTime(draft.startTime, timezone.value),
      endTime: normalizeAiDateTime(draft.endTime, timezone.value),
      deadlineTime: normalizeAiDateTime(draft.deadlineTime, timezone.value),
      remindAt: draft.remindAt ? normalizeAiDateTime(draft.remindAt, timezone.value) : current.remindAt,
    }
    aiParseNotice.value = 'AI 草稿已填入，不会自动保存，请检查后创建'
  } catch (error: any) {
    if (requestId === aiParseRequest) aiParseError.value = `AI 解析失败：${error.message || '服务不可用'}`
  } finally {
    if (requestId === aiParseRequest) aiParsing.value = false
  }
}

async function breakDownTeamTaskWithAi() {
  const form = teamCreateForm.value
  const sourceText = form.title.trim()
  if (!form.teamId || !form.groupId) return store.notify('请先选择团队和分组')
  if (!sourceText) return store.notify('请先输入任务目标再使用 AI 拆解')

  const requestId = ++aiBreakdownRequest
  aiBreaking.value = true
  clearTeamAiState()
  const base: QuickTeamBreakdownBase = {
    teamId: form.teamId,
    groupId: form.groupId,
    startTime: form.startTime,
    remindAt: form.remindAt,
  }
  const formSnapshot = JSON.stringify(form)
  const defaultAssignees = [...form.assigneeUserIds]
  try {
    const result = await store.aiRequest('/team-tasks/breakdown', { text: sourceText })
    if (requestId !== aiBreakdownRequest || activeView.value.kind !== 'create' || createKind.value !== 'team_task') return
    if (JSON.stringify(teamCreateForm.value) !== formSnapshot) {
      teamAiNotice.value = '表单已被修改，本次拆解结果未应用，请重新拆解'
      return
    }
    const tasks = Array.isArray(result.tasks) ? result.tasks : []
    if (!tasks.length) {
      teamAiError.value = 'AI 未能拆解出子任务，请补充目标后重试'
      return
    }
    aiBreakdownBase.value = base
    aiBreakdownTasks.value = tasks.map((task: Record<string, unknown>) => ({
      title: String(task.title || '').trim(),
      selected: true,
      description: String(task.description || '').trim(),
      deadlineTime: normalizeAiDateTime(String(task.deadlineTime || ''), timezone.value),
      assigneeUserIds: [...defaultAssignees],
      error: '',
    }))
    await pushView({ kind: 'team-breakdown', scrollTop: 0 })
    shell.setQuickTimelineExitGuard('confirm', BREAKDOWN_EXIT_MESSAGE)
  } catch (error: any) {
    if (requestId === aiBreakdownRequest) teamAiError.value = `AI 拆解失败：${error.message || '服务不可用'}`
  } finally {
    if (requestId === aiBreakdownRequest) aiBreaking.value = false
  }
}

async function optimizeTeamDescriptionWithAi() {
  const sourceText = teamCreateForm.value.description.trim() || teamCreateForm.value.title.trim()
  if (!sourceText) return store.notify('请先填写任务标题或说明')

  const requestId = ++aiOptimizeRequest
  const formSnapshot = JSON.stringify(teamCreateForm.value)
  aiOptimizingDescription.value = true
  clearTeamAiState()
  try {
    const result = await store.aiRequest('/text/optimize-task-description', { text: sourceText })
    if (requestId !== aiOptimizeRequest || activeView.value.kind !== 'create' || createKind.value !== 'team_task') return
    if (JSON.stringify(teamCreateForm.value) !== formSnapshot) {
      teamAiNotice.value = '表单已被修改，本次优化结果未应用，请重新优化'
      return
    }
    const description = String(result.description || '').trim()
    if (!description) {
      teamAiError.value = 'AI 未生成优化建议'
      return
    }
    teamCreateForm.value.description = description
    teamAiNotice.value = '优化结果已填入说明，不会自动保存'
  } catch (error: any) {
    if (requestId === aiOptimizeRequest) teamAiError.value = `优化失败：${error.message || '服务不可用'}`
  } finally {
    if (requestId === aiOptimizeRequest) aiOptimizingDescription.value = false
  }
}

async function loadTeamCreateContext(teamId: string) {
  const requestId = ++teamCreateContextRequest
  loadedTeamCreateId.value = ''
  teamCreateForm.value.groupId = ''
  teamCreateForm.value.assigneeUserIds = []
  teamCreateGroups.value = []
  teamCreateMembers.value = []
  if (!teamId) return

  teamCreateContextLoading.value = true
  try {
    const [groups, memberData] = await Promise.all([
      store.loadTeamTaskGroups(teamId),
      store.request<{ list: TeamMember[] }>(`/teams/${teamId}/members`),
    ])
    if (requestId !== teamCreateContextRequest || teamCreateForm.value.teamId !== teamId) return
    teamCreateGroups.value = groups
    teamCreateMembers.value = (memberData.list || []).filter(member => member.status === 'active')
    if (groups[0]) teamCreateForm.value.groupId = String(groups[0].id)
    loadedTeamCreateId.value = teamId
  } catch (error: any) {
    if (requestId === teamCreateContextRequest) store.notify(error.message || '加载团队信息失败')
  } finally {
    if (requestId === teamCreateContextRequest) teamCreateContextLoading.value = false
  }
}

async function handleTeamCreateChange(teamId: string) {
  aiBreakdownRequest += 1
  aiOptimizeRequest += 1
  aiBreaking.value = false
  aiOptimizingDescription.value = false
  clearTeamAiState()
  await loadTeamCreateContext(teamId)
}

async function openEntry(entry: QuickEntry) {
  if (entry.sourceType === 'schedule') {
    scheduleDetail.value = entry.raw as Schedule
    await pushView({ kind: 'schedule-detail', id: entry.raw.id, scrollTop: 0 })
    await loadScheduleDetail(entry.raw.id)
    return
  }

  await pushView({ kind: 'task-detail', id: entry.raw.id, scrollTop: 0 })
  await loadTeamTaskDetail(entry.raw.id)
}

async function submitTeamTaskComplete(id: number, fatigueLevel?: number) {
  const body = fatigueLevel !== undefined ? JSON.stringify({ fatigueLevel }) : undefined
  await store.request(`/team-tasks/${id}/complete`, { method: 'POST', body })
  await refreshQuickWorkspace()
  if (activeView.value.kind === 'task-detail' && activeView.value.id === id) {
    await loadTeamTaskDetail(id)
  }
}

async function confirmComplete() {
  if (!pendingCompleteTask.value || actionBusy.value) return
  if (completingFatigueLevel.value === null) {
    store.notify('请选择完成疲劳程度')
    return
  }
  actionBusy.value = true
  const id = pendingCompleteTask.value.id
  try {
    await submitTeamTaskComplete(id, completingFatigueLevel.value)
    store.notify('任务已完成')
    pendingCompleteTask.value = null
  } catch (error: any) {
    store.notify(error.message || '操作失败')
  } finally {
    actionBusy.value = false
  }
}

function closeCompletionModal() {
  if (actionBusy.value) return
  pendingCompleteTask.value = null
}

async function performAction(entry: QuickEntry) {
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    if (entry.sourceType === 'schedule') {
      const schedule = entry.raw as Schedule
      const endpoint = schedule.status === 'pending' ? 'complete' : schedule.status === 'completed' ? 'uncomplete' : 'restore'
      await store.request(`/schedules/${entry.raw.id}/${endpoint}`, { method: 'PUT' })
      await refreshQuickWorkspace()
      return
    }
    const task = entry.raw as MyTask
    if (!isActionableTeamTask(task)) return
    const action = task.assignStatus === 'pending' ? 'accept' : task.assignStatus === 'accepted' ? 'complete' : ''
    if (action === 'complete') {
      if (fatigueTrackingOn.value) {
        pendingCompleteTask.value = { id: task.id, title: task.title }
        completingFatigueLevel.value = null
        return
      }
      await submitTeamTaskComplete(task.id)
    } else if (action) {
      await store.request(`/team-tasks/${task.id}/${action}`, { method: 'POST' })
      await refreshQuickWorkspace()
    }
  } catch (error: any) {
    store.notify(error.message || '操作失败')
  } finally {
    actionBusy.value = false
  }
}

function validateSchedule(form: ScheduleForm) {
  if (!form.title.trim()) return '请输入标题'
  if (!form.groupId) return '请选择分组'
  if (form.timeType === 'point_event' && !form.startTime) return '请选择发生时间'
  if (form.timeType === 'deadline_task' && !form.deadlineTime) return '请选择截止时间'
  if (form.timeType === 'duration_task') {
    if (!form.startTime || !form.endTime) return '请选择开始和结束时间'
    if (new Date(form.endTime).getTime() <= new Date(form.startTime).getTime()) return '结束时间必须晚于开始时间'
  }
  return ''
}

async function submitCreateSchedule() {
  const error = validateSchedule(createForm.value)
  if (error) return store.notify(error)
  formBusy.value = true
  try {
    await store.request('/schedules', { method: 'POST', body: JSON.stringify(schedulePayload(createForm.value)) })
    createForm.value = newScheduleForm()
    ensureCreateFormDefaults()
    await refreshQuickWorkspace()
    store.notify('日程已创建')
    clearAiParseState()
    await returnToTimeline()
  } catch (error: any) {
    store.notify(error.message || '创建日程失败')
  } finally {
    formBusy.value = false
  }
}

async function submitCreateTeamTask() {
  const form = teamCreateForm.value
  if (!form.teamId || !form.groupId || !form.title.trim()) return store.notify('请选择团队、分组并填写标题')
  if (!form.assigneeUserIds.length) return store.notify('请选择至少一名执行人')
  if (form.startTime && form.deadlineTime && new Date(form.deadlineTime).getTime() < new Date(form.startTime).getTime()) {
    return store.notify('截止时间不能早于开始时间')
  }

  formBusy.value = true
  try {
    const teamId = form.teamId
    const payload = taskPayload(form)
    payload.teamId = Number(form.teamId)
    payload.groupId = Number(form.groupId)
    const created = await store.request<TeamTask>('/team-tasks', { method: 'POST', body: JSON.stringify(payload) })
    teamCreateForm.value = newTeamTaskForm(teamId)
    await Promise.all([refreshQuickWorkspace(), loadTeamCreateContext(teamId)])
    store.notify(created.approvalStatus === 'pending' ? '任务已提交，等待团队管理员审批' : '团队任务已创建')
    clearTeamAiState()
    await returnToTimeline()
  } catch (error: any) {
    store.notify(error.message || '创建团队任务失败')
  } finally {
    formBusy.value = false
  }
}

function cancelTeamBreakdown() {
  if (aiBreakdownSaving.value || activeView.value.kind !== 'team-breakdown') return
  shell.setQuickTimelineExitGuard('none')
  goBack()
}

async function submitTeamBreakdown() {
  if (aiBreakdownSaving.value || !aiBreakdownBase.value) return
  const base = { ...aiBreakdownBase.value }
  const selected = aiBreakdownTasks.value.filter(task => task.selected)
  if (!selected.length) return store.notify('请至少选择一条子任务')

  aiBreakdownTasks.value = aiBreakdownTasks.value.map(task => ({ ...task, error: '' }))
  let created = 0
  let pendingApproval = 0
  const failed = new Set<QuickTeamBreakdownTask>()
  aiBreakdownSaving.value = true
  shell.setQuickTimelineExitGuard('blocked')
  try {
    for (const task of aiBreakdownTasks.value.filter(item => item.selected)) {
      if (!task.title.trim()) {
        task.error = '请填写子任务标题'
        failed.add(task)
        continue
      }
      if (!task.assigneeUserIds.length) {
        task.error = '请至少选择一名执行人'
        failed.add(task)
        continue
      }
      if (base.startTime && task.deadlineTime
        && new Date(task.deadlineTime).getTime() < new Date(base.startTime).getTime()) {
        task.error = '截止时间不能早于任务开始时间'
        failed.add(task)
        continue
      }

      try {
        const payload = taskPayload({
          teamId: base.teamId,
          groupId: base.groupId,
          title: task.title.trim(),
          description: task.description,
          startTime: base.startTime,
          deadlineTime: task.deadlineTime,
          remindAt: base.remindAt,
          assigneeUserIds: [...task.assigneeUserIds],
        })
        payload.teamId = Number(base.teamId)
        payload.groupId = Number(base.groupId)
        const result = await store.request<TeamTask>('/team-tasks', { method: 'POST', body: JSON.stringify(payload) })
        created += 1
        if (result.approvalStatus === 'pending') pendingApproval += 1
      } catch (error: any) {
        const numericCode = Number(error.code)
        const resultUncertain = error.code === 'NETWORK_UNAVAILABLE'
          || !Number.isFinite(numericCode)
          || numericCode === 200
          || numericCode >= 500
        if (resultUncertain) {
          task.selected = false
          task.error = '网络中断，创建结果未知。请先刷新时间轴确认；若未创建，再勾选此项重试'
        } else {
          task.error = error.message || '创建失败，请重试'
        }
        failed.add(task)
      }
    }

    if (created) await refreshQuickWorkspace()
    aiBreakdownTasks.value = aiBreakdownTasks.value.filter(task => !task.selected || failed.has(task))
    if (failed.size) {
      const successText = pendingApproval ? `已提交 ${created} 条待审批任务` : `已创建 ${created} 条`
      store.notify(created ? `${successText}，${failed.size} 条失败，请修正后重试` : '子任务创建失败，请检查提示后重试')
      return
    }

    const teamId = base.teamId
    teamCreateForm.value = newTeamTaskForm(teamId)
    await loadTeamCreateContext(teamId)
    aiBreakdownBase.value = null
    aiBreakdownTasks.value = []
    shell.setQuickTimelineExitGuard('none')
    clearTeamAiState()
    store.notify(pendingApproval ? `已提交 ${created} 条子任务，等待团队管理员审批` : `已创建 ${created} 条子任务`)
    await returnToTimeline()
  } finally {
    aiBreakdownSaving.value = false
    if (activeView.value.kind === 'team-breakdown' && aiBreakdownTasks.value.length) {
      shell.setQuickTimelineExitGuard('confirm', BREAKDOWN_EXIT_MESSAGE)
    }
  }
}

async function loadScheduleDetail(id: number) {
  const requestId = ++detailRequest
  detailLoading.value = true
  scheduleDetail.value = null
  try {
    const detail = await store.request<Schedule>(`/schedules/${id}`)
    if (requestId === detailRequest && activeView.value.kind.startsWith('schedule') && activeView.value.id === id) {
      scheduleDetail.value = detail
    }
  } catch (error: any) {
    if (requestId === detailRequest) store.notify(error.message || '加载日程详情失败')
  } finally {
    if (requestId === detailRequest) detailLoading.value = false
  }
}

async function runScheduleAction(action: string) {
  if (!scheduleDetail.value || actionBusy.value) return
  // P04：进度任务一律走每日进度流程，禁止调用普通完成接口。
  if (action === 'complete' && scheduleDetail.value.progressTrackingEnabled) {
    quickProgressPanel.value?.openSubmit(100)
    return
  }
  actionBusy.value = true
  try {
    const id = scheduleDetail.value.id
    await store.request(`/schedules/${id}/${action}`, { method: 'PUT' })
    await Promise.all([loadScheduleDetail(id), refreshQuickWorkspace()])
    store.notify('日程状态已更新')
  } catch (error: any) {
    store.notify(error.message || '操作失败')
  } finally {
    actionBusy.value = false
  }
}

async function openScheduleEdit() {
  if (!scheduleDetail.value) return
  const item = scheduleDetail.value
  scheduleEditScope.value = 'occurrence'
  scheduleEditForm.value = {
    title: item.title,
    description: item.description || '',
    groupId: String(item.groupId || ''),
    groupName: item.groupName || '',
    timeType: item.timeType,
    startTime: isoToZonedDatetimeLocal(item.startTime, timezone.value),
    endTime: isoToZonedDatetimeLocal(item.endTime, timezone.value),
    deadlineTime: isoToZonedDatetimeLocal(item.deadlineTime, timezone.value),
    remindAt: isoToZonedDatetimeLocal(item.pendingReminders?.[0]?.remindAt || item.remindAt || '', timezone.value),
    urgencyLevel: item.urgencyLevel || 3,
    fatigueLevel: item.fatigueLevel || 3,
    rrule: item.rrule || '',
    excludedDates: [...(item.excludedDates || [])],
    progressTrackingEnabled: Boolean(item.progressTrackingEnabled),
  }
  initialScheduleReminder.value = scheduleEditForm.value.remindAt
  await pushView({ kind: 'schedule-edit', id: item.id, scrollTop: 0 })
}

async function saveScheduleEdit() {
  if (!scheduleDetail.value) return
  const error = validateSchedule(scheduleEditForm.value)
  if (error) return store.notify(error)
  const scheduleId = scheduleDetail.value.id
  const recurring = scheduleIsRecurring.value
  const editScope = recurring ? scheduleEditScope.value : null
  formBusy.value = true
  try {
    const payload = schedulePayload(scheduleEditForm.value)
    if (scheduleEditForm.value.remindAt === initialScheduleReminder.value) delete payload.remindAt
    else payload.remindAt = scheduleEditForm.value.remindAt
      ? zonedDatetimeLocalToIso(scheduleEditForm.value.remindAt, timezone.value)
      : ''
    let endpoint = `/schedules/${scheduleId}`
    if (editScope === 'series') {
      if (!scheduleDetail.value.seriesId) throw new Error('重复系列标识缺失，无法更新整个系列')
      endpoint = `/schedules/series/${encodeURIComponent(scheduleDetail.value.seriesId)}`
    } else if (editScope === 'occurrence') {
      // The occurrence endpoint deliberately rejects series-only fields.
      delete payload.rrule
      delete payload.excludedDates
      endpoint = `/schedules/${scheduleId}/occurrence`
    }
    await store.request(endpoint, { method: 'PUT', body: JSON.stringify(payload) })
    await refreshQuickWorkspace()
    store.notify(editScope === 'series' ? '重复系列已更新' : editScope === 'occurrence' ? '此实例已更新' : '日程已更新')
    await loadScheduleDetail(scheduleId)
    await goBack()
  } catch (error: any) {
    store.notify(error.message || '更新日程失败')
  } finally {
    formBusy.value = false
  }
}

async function loadTeamTaskDetail(id: number) {
  const requestId = ++detailRequest
  detailLoading.value = true
  teamTaskDetail.value = null
  try {
    const detail = await store.request<TeamTask>(`/team-tasks/${id}`)
    if (requestId === detailRequest && activeView.value.kind.startsWith('task') && activeView.value.id === id) {
      teamTaskDetail.value = detail
    }
  } catch (error: any) {
    if (requestId === detailRequest) store.notify(error.message || '加载团队任务详情失败')
  } finally {
    if (requestId === detailRequest) detailLoading.value = false
  }
}

async function runTeamTaskAction(action: string) {
  if (!teamTaskDetail.value || actionBusy.value) return
  if (action === 'complete') {
    if (fatigueTrackingOn.value) {
      pendingCompleteTask.value = { id: teamTaskDetail.value.id, title: teamTaskDetail.value.title }
      completingFatigueLevel.value = null
      return
    }
    actionBusy.value = true
    try {
      await submitTeamTaskComplete(teamTaskDetail.value.id)
      store.notify('任务已完成')
    } catch (error: any) {
      store.notify(error.message || '操作失败')
    } finally {
      actionBusy.value = false
    }
    return
  }
  actionBusy.value = true
  try {
    const id = teamTaskDetail.value.id
    await store.request(`/team-tasks/${id}/${action}`, { method: 'POST' })
    await refreshQuickWorkspace()
    await loadTeamTaskDetail(id)
    store.notify('任务状态已更新')
  } catch (error: any) {
    store.notify(error.message || '操作失败')
  } finally {
    actionBusy.value = false
  }
}

async function openTaskEdit() {
  if (!teamTaskDetail.value || !canManageTeamTask.value) return
  const item = teamTaskDetail.value
  teamTaskGroups.value = await store.loadTeamTaskGroups(item.teamId)
  taskEditForm.value = {
    title: item.title,
    description: item.description || '',
    groupId: String(item.groupId || ''),
    startTime: isoToZonedDatetimeLocal(item.startTime, timezone.value),
    deadlineTime: isoToZonedDatetimeLocal(item.deadlineTime, timezone.value),
    remindAt: isoToZonedDatetimeLocal(item.pendingReminders?.[0]?.remindAt || '', timezone.value),
  }
  initialTaskReminder.value = taskEditForm.value.remindAt
  await pushView({ kind: 'task-edit', id: item.id, scrollTop: 0 })
}

async function saveTaskEdit() {
  if (!teamTaskDetail.value || !taskEditForm.value.title.trim() || !taskEditForm.value.groupId) {
    store.notify('请填写标题并选择分组')
    return
  }
  if (taskEditForm.value.startTime && taskEditForm.value.deadlineTime
    && new Date(taskEditForm.value.deadlineTime).getTime() < new Date(taskEditForm.value.startTime).getTime()) {
    store.notify('截止时间不能早于开始时间')
    return
  }
  formBusy.value = true
  try {
    const form = taskEditForm.value
    const taskId = teamTaskDetail.value.id
    const teamId = teamTaskDetail.value.teamId
    const payload: Record<string, unknown> = taskPayload({
      title: form.title.trim(),
      description: form.description,
      groupId: form.groupId,
      startTime: form.startTime,
      deadlineTime: form.deadlineTime,
      remindAt: form.remindAt,
    })
    payload.groupId = Number(form.groupId)
    if (form.remindAt === initialTaskReminder.value) delete payload.remindAt
    await store.request(`/team-tasks/${taskId}`, { method: 'PUT', body: JSON.stringify(payload) })
    await Promise.all([loadTeamTaskDetail(taskId), refreshQuickWorkspace(), store.loadTeamTasks(teamId)])
    store.notify('团队任务已更新')
    await goBack()
  } catch (error: any) {
    store.notify(error.message || '更新失败')
  } finally {
    formBusy.value = false
  }
}

async function openFullWorkspace() {
  if (aiBreakdownSaving.value) return
  let target = quickSource.value === 'personal'
    ? `/schedules?view=${encodeURIComponent(quickViewMode.value)}&status=${encodeURIComponent(quickScheduleStatus.value)}`
    : '/tasks'
  if (activeView.value.kind === 'create') {
    if (createKind.value === 'schedule') {
      Object.assign(store.scheduleForm, createForm.value)
      store.openScheduleModal()
      target = '/schedules'
    } else {
      Object.assign(store.taskForm, teamCreateForm.value)
      target = '/tasks'
    }
  } else if (activeView.value.kind === 'team-breakdown') {
    target = '/tasks'
  } else if (activeView.value.kind === 'schedule-detail' || activeView.value.kind === 'schedule-edit') {
    target = `/schedules/${activeView.value.id}`
  } else if (activeView.value.kind === 'task-detail' || activeView.value.kind === 'task-edit') {
    target = `/tasks/${activeView.value.id}`
  } else if (activeView.value.kind === 'fatigue-survey') {
    target = `/fatigue/survey?date=${encodeURIComponent(activeView.value.localDate)}`
  }
  await router.push(target)
  await shell.setQuickTimeline(false)
}

function handleEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (pendingCompleteTask.value) {
    if (!actionBusy.value) pendingCompleteTask.value = null
    event.preventDefault()
    return
  }
  if (viewStack.value.length <= 1 || aiBreakdownSaving.value) return
  event.preventDefault()
  goBack()
}

watch(() => store.profile?.id, (id, previousId) => {
  if (id !== previousId) {
    for (const mode of ['time', 'group', 'urgency', 'fatigue'] as ScheduleViewMode[]) {
      quickPersonalRequests[mode] += 1
      quickScheduleResults.value[mode] = null
      quickPersonalLoading.value[mode] = false
      quickPersonalErrors.value[mode] = ''
    }
    quickTeamRequest += 1
    quickTasks.value = []
    quickTeamLoading.value = false
    quickTeamLoaded.value = false
    quickTeamError.value = ''
    quickAcceptedScheduleRevision.value = null
    pendingCompleteTask.value = null
    viewStack.value = [{ kind: 'timeline', scrollTop: 0 }]
    quickViewMode.value = 'time'
    quickSource.value = 'personal'
    quickScrollPositions.value = defaultQuickScrollPositions()
  }
  if (!id) return
  loadQuickPreferences()
  loadQuickTimelineData()
}, { immediate: true })

watch(() => shell.pendingQuickTarget.value, () => processPendingQuickTarget(), { immediate: true })
watch(() => activeView.value.kind, () => processPendingQuickTarget())
watch(() => shell.quickRefreshRevision.value, () => refreshQuickWorkspace())
watch(todayKey, (value, previous) => {
  if (value !== previous) refreshQuickWorkspace()
})

onMounted(() => {
  timer = window.setInterval(() => { now.value = Date.now() }, 60_000)
  window.addEventListener('keydown', handleEscape)
})

onUnmounted(() => {
  rememberTimelineScroll()
  persistQuickPreferences()
  shell.setQuickTimelineExitGuard('none')
  invalidateAiRequests()
  if (timer) window.clearInterval(timer)
  window.removeEventListener('keydown', handleEscape)
})
</script>

<template>
  <section ref="workspaceRoot" class="desktop-quick-timeline" aria-label="快捷任务工作区" @scroll.passive="handleWorkspaceScroll">
    <template v-if="activeView.kind === 'timeline'">
      <header class="quick-timeline-header">
        <div>
          <p>{{ quickSource === 'personal' ? '个人日程' : '团队任务' }}</p>
          <h1>{{ todayLabel }}</h1>
          <span>{{ stats.active ? `${stats.active} 项进行中` : `${quickHeaderCount} 项已安排` }}<b v-if="stats.overdue">{{ stats.overdue }} 项逾期</b></span>
        </div>
        <div class="quick-header-actions">
          <button type="button" class="icon-button" title="刷新数据" aria-label="刷新数据" :disabled="store.loading || quickRefreshLoading" @click="refreshQuickWorkspace"><RefreshCw :class="{ spinning: store.loading || quickRefreshLoading }" :size="17" /></button>
          <button type="button" class="icon-button" :title="quickSource === 'personal' ? '新建个人日程' : '打开团队任务'" :aria-label="quickSource === 'personal' ? '新建个人日程' : '打开团队任务'" @click="openCreateForSource"><Plus :size="18" /></button>
          <button type="button" class="icon-button" title="打开完整工作台" aria-label="打开完整工作台" @click="openFullWorkspace"><PanelTopOpen :size="17" /></button>
        </div>
      </header>

      <div class="quick-source-switch" role="tablist" aria-label="快捷窗口来源">
        <button type="button" :class="{ active: quickSource === 'personal' }" role="tab" :aria-selected="quickSource === 'personal'" @click="selectQuickSource('personal')"><CalendarClock :size="14" />个人日程</button>
        <button type="button" :class="{ active: quickSource === 'team' }" role="tab" :aria-selected="quickSource === 'team'" @click="selectQuickSource('team')"><UserRound :size="14" />团队任务</button>
      </div>

      <div v-if="quickDataError" class="quick-data-warning">
        <span>{{ quickDataLoaded ? '数据刷新失败，当前显示上次成功结果' : quickDataError }}</span>
        <button type="button" @click="loadQuickTimelineData(true, quickViewMode, quickSource)">重试</button>
      </div>

      <template v-if="quickSource === 'personal'">
        <div class="quick-view-switch" role="tablist" aria-label="个人日程状态">
          <button v-for="option in quickStatusOptions" :key="option.value" type="button" :class="{ active: quickScheduleStatus === option.value }" role="tab" :aria-selected="quickScheduleStatus === option.value" @click="selectQuickScheduleStatus(option.value)">{{ option.label }}</button>
        </div>
        <div class="quick-view-switch" role="tablist" aria-label="个人日程查看方式">
          <button v-for="option in quickViewOptions" :key="option.value" type="button" :class="{ active: quickViewMode === option.value }" role="tab" :aria-selected="quickViewMode === option.value" @click="selectQuickView(option.value)"><component :is="option.icon" :size="13" />{{ option.label }}</button>
        </div>

        <section class="quick-fatigue-summary" role="button" tabindex="0" @click="openFatigueSurvey" @keydown.enter="openFatigueSurvey" @keydown.space.prevent="openFatigueSurvey">
          <div><span>个人预计负荷</span><strong>{{ store.fatigueDaily?.predictedScore ?? '--' }}<small> 分 · {{ fatigueStateLabel }}</small></strong></div>
          <div><span>{{ quickScheduleStatus === 'completed' ? '完成负荷' : quickScheduleStatus === 'cancelled' ? '取消记录' : '计划 / 上限' }}</span><strong>{{ quickScheduleStatus === 'completed' ? `${quickHistoryLoad} 点` : quickScheduleStatus === 'cancelled' ? `${quickScheduleResult?.total || 0} 项` : `${store.fatigueDaily?.plannedLoad ?? 0} / ${store.fatigueDaily?.capacity75 ?? store.fatigueProfile?.capacity75 ?? 18}` }}</strong></div>
          <div><span>调查</span><strong>{{ fatigueSurveyStatus }}</strong></div>
          <ChevronRight :size="16" />
          <span class="quick-load-track"><i :style="{ width: `${quickLoadPercent}%` }"></i></span>
        </section>

        <div v-if="fatigueAlertText" class="quick-load-alert"><AlertTriangle :size="15" /><span>{{ fatigueAlertText }}</span></div>

        <section v-if="store.fatigueSurveyToday?.pending" class="quick-survey-banner">
          <div><BatteryMedium :size="16" /><span><strong>今日日终调查待填写</strong><small>记录实际感受，帮助校准个人承受上限</small></span></div>
          <button type="button" @click="openFatigueSurvey">填写</button>
        </section>

        <template v-if="quickViewMode !== 'time' || quickScheduleStatus !== 'pending'">
          <section v-if="quickDataLoading && !quickDataLoaded" class="quick-empty-state"><RefreshCw class="spinning" :size="22" /><span>正在加载个人日程...</span></section>
          <section v-else-if="quickDataError && !quickDataLoaded" class="quick-empty-state"><AlertTriangle :size="24" /><strong>暂时无法加载个人日程</strong><button type="button" @click="loadQuickTimelineData(true, quickViewMode, 'personal')">重试</button></section>
          <section v-else-if="!personalSections.length" class="quick-empty-state"><CalendarClock :size="24" /><strong>当前视图没有个人日程</strong><button type="button" @click="openCreateSchedule">新建日程</button></section>
          <section v-for="section in personalSections" v-else :key="section.key" class="quick-personal-section">
            <div class="quick-section-heading"><component :is="quickViewMode === 'group' ? Layers3 : quickViewMode === 'urgency' ? AlertTriangle : BatteryMedium" :size="15" /><span>{{ section.label }}</span><em>{{ sectionLoadText(section) }}</em></div>
            <article v-for="entry in section.items.slice(0, 8)" :key="entry.key" class="quick-unscheduled-item quick-personal-item" @click="openEntry(entry)">
              <div><strong>{{ entry.raw.title }}</strong><small>{{ formatTime(displayScheduleTime(entry.raw as Schedule)) }} · 紧急 {{ (entry.raw as Schedule).urgencyLevel }} · 疲劳 {{ displayFatigueLevel(entry.raw as Schedule) }} / {{ displayFatigueWeight(entry.raw as Schedule) }} 点</small></div>
              <button v-if="canAct(entry)" type="button" class="quick-action" :title="actionLabel(entry)" :aria-label="actionLabel(entry)" @click.stop="performAction(entry)"><component :is="actionIcon(entry)" :size="15" /></button>
              <ChevronRight v-else :size="16" />
            </article>
            <button v-if="section.total > Math.min(section.items.length, 8)" type="button" class="quick-section-more" @click="openFullScheduleView">查看全部 {{ section.total }} 项</button>
          </section>
        </template>
      </template>

      <template v-if="quickSource === 'team' || (quickViewMode === 'time' && quickScheduleStatus === 'pending')">
      <section v-if="overdueEntries.length" class="quick-overdue-section" aria-label="已逾期任务">
        <div class="quick-section-heading"><Flag :size="15" /><span>已逾期</span><em>{{ overdueEntries.length }}</em></div>
        <article v-for="entry in overdueEntries.slice(0, 8)" :key="entry.key" class="quick-overdue-item" @click="openEntry(entry)">
          <span class="quick-overdue-time">{{ formatClock(entry.range.endAt) }}</span>
          <div><strong>{{ entry.raw.title }}</strong><small>{{ entry.sourceLabel }}</small></div>
          <button v-if="canAct(entry)" type="button" class="quick-action" :title="actionLabel(entry)" :aria-label="actionLabel(entry)" @click.stop="performAction(entry)"><component :is="actionIcon(entry)" :size="15" /></button>
          <ChevronRight v-else :size="16" />
        </article>
        <button v-if="overdueEntries.length > 8 && quickSource === 'personal'" type="button" class="quick-section-more" @click="openFullScheduleView">查看全部 {{ overdueEntries.length }} 项</button>
      </section>

      <section class="quick-timeline-track" aria-label="今日时间轴">
        <div v-if="quickDataLoading && !quickDataLoaded" class="quick-empty-state"><RefreshCw class="spinning" :size="22" /><span>正在加载{{ quickSource === 'personal' ? '个人日程' : '团队任务' }}...</span></div>
        <div v-else-if="quickDataError && !quickDataLoaded" class="quick-empty-state"><AlertTriangle :size="24" /><strong>暂时无法加载{{ quickSource === 'personal' ? '个人日程' : '团队任务' }}</strong><button type="button" @click="loadQuickTimelineData(true, quickViewMode, quickSource)">重试</button></div>
        <div v-else-if="timelineRows.length === 1" class="quick-empty-state">
          <CalendarClock :size="25" />
          <strong>今天没有定时安排</strong>
           <button type="button" @click="openCreateForSource">{{ quickSource === 'personal' ? '新建日程' : '打开团队任务' }}</button>
        </div>
        <template v-else>
          <article v-for="(row, index) in timelineRows" :key="row.type === 'now' ? `now-${index}` : row.entry.key" :class="['quick-timeline-row', row.type === 'now' ? 'now' : `kind-${row.entry.range.kind}`, row.type === 'item' ? `state-${row.entry.presentation}` : '']">
            <template v-if="row.type === 'now'">
              <span class="quick-time-label">现在</span><span class="quick-now-node"><i></i></span><div class="quick-now-line"></div>
            </template>
            <template v-else>
              <span class="quick-time-label">{{ formatTimelineTime(row.entry) }}</span>
              <span class="quick-axis-node"><i></i></span>
              <div class="quick-timeline-card-wrap">
                <button type="button" class="quick-timeline-card" @click="openEntry(row.entry)">
                  <span class="quick-card-meta">
                    <span class="quick-kind-label"><CircleDot v-if="row.entry.range.kind === 'point_event'" :size="13" /><Flag v-else-if="row.entry.range.kind === 'deadline_task'" :size="13" /><Clock3 v-else :size="13" />{{ kindLabel(row.entry.range.kind) }}</span>
                    <span :class="['quick-status-label', row.entry.presentation]">{{ presentationLabel(row.entry.presentation) }}</span>
                  </span>
                  <strong>{{ row.entry.raw.title }}</strong><small>{{ row.entry.sourceLabel }}</small>
                </button>
                <button v-if="canAct(row.entry)" type="button" class="quick-card-action" :title="actionLabel(row.entry)" :aria-label="actionLabel(row.entry)" @click="performAction(row.entry)"><component :is="actionIcon(row.entry)" :size="15" /></button>
              </div>
            </template>
          </article>
        </template>
        <button v-if="timelineEntries.length > visibleTimelineEntries.length && quickSource === 'personal'" type="button" class="quick-section-more" @click="openFullScheduleView">查看全部 {{ timelineEntries.length }} 项定时安排</button>
      </section>

      <section v-if="unscheduledEntries.length" class="quick-unscheduled-section" aria-label="待安排任务">
        <div class="quick-section-heading"><ListTodo :size="15" /><span>待安排</span><em>{{ unscheduledEntries.length }}</em></div>
        <article v-for="entry in unscheduledEntries.slice(0, 8)" :key="entry.key" class="quick-unscheduled-item" @click="openEntry(entry)">
          <div><strong>{{ entry.raw.title }}</strong><small>{{ entry.sourceLabel }}</small></div>
          <button v-if="canAct(entry)" type="button" class="quick-action" :title="actionLabel(entry)" :aria-label="actionLabel(entry)" @click.stop="performAction(entry)"><component :is="actionIcon(entry)" :size="15" /></button>
          <ChevronRight v-else :size="16" />
        </article>
        <button v-if="unscheduledEntries.length > 8 && quickSource === 'personal'" type="button" class="quick-section-more" @click="openFullScheduleView">查看全部 {{ unscheduledEntries.length }} 项</button>
      </section>
      </template>

      <button v-if="quickSource === 'personal' && quickDataLoaded" type="button" class="quick-view-all" @click="openFullScheduleView"><PanelTopOpen :size="14" />查看完整个人日程</button>
    </template>

    <template v-else>
      <header class="quick-workspace-header">
        <button type="button" class="icon-button" title="返回" aria-label="返回" @click="goBack"><ArrowLeft :size="18" /></button>
        <div><p>{{ nestedViewKicker }}</p><h1>{{ nestedViewTitle }}</h1></div>
        <button v-if="activeView.kind !== 'team-breakdown'" type="button" class="icon-button" title="打开完整工作台" aria-label="打开完整工作台" @click="openFullWorkspace"><PanelTopOpen :size="17" /></button>
      </header>

      <template v-if="activeView.kind === 'create'">
        <div class="quick-create-kind" role="tablist" aria-label="创建类型">
          <button type="button" :class="{ active: createKind === 'schedule' }" role="tab" :aria-selected="createKind === 'schedule'" :disabled="aiParsing || aiBreaking || aiOptimizingDescription" @click="setCreateKind('schedule')"><CalendarClock :size="15" />个人日程</button>
          <button type="button" :class="{ active: createKind === 'team_task' }" role="tab" :aria-selected="createKind === 'team_task'" :disabled="aiParsing || aiBreaking || aiOptimizingDescription" @click="setCreateKind('team_task')"><UserRound :size="15" />团队任务</button>
        </div>
        <DesktopQuickScheduleEditor v-if="createKind === 'schedule'" v-model="createForm" :groups="store.taskGroups" :busy="formBusy" :reminder-presets="store.notificationPreferences.reminderPresetMinutes" :timezone="timezone" :ai-enabled="true" :ai-busy="aiParsing" :ai-error="aiParseError" :ai-notice="aiParseNotice" submit-label="创建日程" @ai-parse="parseCreateWithAi" @error="store.notify" @submit="submitCreateSchedule" />
        <DesktopQuickTeamTaskCreator v-else v-model="teamCreateForm" :teams="store.teams" :groups="teamCreateGroups" :members="teamCreateMembers" :busy="formBusy" :loading-context="teamCreateContextLoading" :reminder-presets="store.notificationPreferences.reminderPresetMinutes" :timezone="timezone" :ai-breakdown-busy="aiBreaking" :ai-optimize-busy="aiOptimizingDescription" :ai-error="teamAiError" :ai-notice="teamAiNotice" @ai-breakdown="breakDownTeamTaskWithAi" @ai-optimize="optimizeTeamDescriptionWithAi" @error="store.notify" @team-change="handleTeamCreateChange" @submit="submitCreateTeamTask" />
      </template>
      <DesktopQuickTeamBreakdown v-else-if="activeView.kind === 'team-breakdown'" v-model="aiBreakdownTasks" :members="teamCreateMembers" :busy="aiBreakdownSaving" @cancel="cancelTeamBreakdown" @submit="submitTeamBreakdown" />
      <DesktopQuickFatigueSurvey v-else-if="activeView.kind === 'fatigue-survey'" :local-date="activeView.localDate" @close="goBack" @updated="refreshQuickWorkspace" />
      <template v-else-if="activeView.kind === 'schedule-edit'">
        <div v-if="scheduleIsRecurring" class="quick-edit-scope" role="radiogroup" aria-label="编辑范围">
          <span>编辑范围</span>
          <label :class="{ active: scheduleEditScope === 'occurrence' }"><input v-model="scheduleEditScope" type="radio" value="occurrence" :disabled="formBusy" />仅此实例</label>
          <label :class="{ active: scheduleEditScope === 'series' }"><input v-model="scheduleEditScope" type="radio" value="series" :disabled="formBusy" />整个系列</label>
        </div>
        <DesktopQuickScheduleEditor v-model="scheduleEditForm" :groups="store.taskGroups" :busy="formBusy" :reminder-presets="store.notificationPreferences.reminderPresetMinutes" :timezone="timezone" :repeat-enabled="!scheduleIsRecurring || scheduleEditScope === 'series'" submit-label="保存修改" @error="store.notify" @submit="saveScheduleEdit" />
      </template>
      <DesktopQuickTaskEditor v-else-if="activeView.kind === 'task-edit'" v-model="taskEditForm" :groups="teamTaskGroups" :busy="formBusy" :reminder-presets="store.notificationPreferences.reminderPresetMinutes" :timezone="timezone" @error="store.notify" @submit="saveTaskEdit" />

      <div v-else-if="detailLoading" class="quick-detail-loading"><RefreshCw class="spinning" :size="20" /><span>加载详情...</span></div>

      <section v-else-if="activeView.kind === 'schedule-detail' && scheduleDetail" class="quick-detail-view">
        <header class="quick-detail-hero">
          <div><span :class="['quick-status-label', schedulePresentation]">{{ presentationLabel(schedulePresentation) }}</span><h2>{{ scheduleDetail.title }}</h2><p>{{ scheduleDetail.groupName || '未分组' }} · {{ kindLabel(scheduleDetail.timeType) }}</p></div>
          <button type="button" class="icon-button" title="编辑日程" aria-label="编辑日程" @click="openScheduleEdit"><Edit3 :size="16" /></button>
        </header>
        <p v-if="scheduleDetail.description" class="quick-detail-description">{{ scheduleDetail.description }}</p>
        <dl class="quick-detail-list">
          <div v-if="scheduleDetail.timeType === 'point_event'"><dt>发生时间</dt><dd>{{ formatTime(scheduleDetail.startTime) }}</dd></div>
          <div v-if="scheduleDetail.timeType === 'deadline_task'"><dt>截止时间</dt><dd>{{ formatTime(scheduleDetail.deadlineTime) }}<small v-if="scheduleDetail.status === 'pending'">{{ countdown(scheduleDetail.deadlineTime) }}</small></dd></div>
          <template v-if="scheduleDetail.timeType === 'duration_task'"><div><dt>开始时间</dt><dd>{{ formatTime(scheduleDetail.startTime) }}</dd></div><div><dt>结束时间</dt><dd>{{ formatTime(scheduleDetail.endTime) }}<small v-if="scheduleDetail.status === 'pending'">{{ countdown(scheduleDetail.endTime) }}</small></dd></div></template>
          <div><dt>日程状态</dt><dd>{{ statusLabel(scheduleDetail.status) }}</dd></div>
          <div v-if="scheduleDetail.pendingReminders?.length"><dt>下次提醒</dt><dd>{{ formatTime(scheduleDetail.pendingReminders[0].remindAt) }}</dd></div>
        </dl>
        <ScheduleProgressPanel
          v-if="scheduleDetail.progressTrackingEnabled"
          ref="quickProgressPanel"
          class="quick-detail-progress"
          :schedule-id="scheduleDetail.id"
          :status="scheduleDetail.status"
          :default-fatigue-level="scheduleDetail.fatigueLevel || 3"
          @updated="loadScheduleDetail(scheduleDetail.id)"
        />
        <footer class="quick-detail-actions">
          <button v-if="scheduleDetail.status === 'pending' && !scheduleDetail.progressTrackingEnabled" class="primary" :disabled="actionBusy" @click="runScheduleAction('complete')"><Check :size="16" />完成</button>
          <button v-if="scheduleDetail.status === 'pending' && scheduleDetail.progressTrackingEnabled" class="primary" :disabled="actionBusy" @click="quickProgressPanel?.openSubmit(100)"><Check :size="16" />{{ progressCompletionActionLabel(scheduleDetail.progressPercent) }}</button>
          <button v-if="scheduleDetail.status === 'completed'" :disabled="actionBusy" @click="runScheduleAction('uncomplete')"><RotateCcw :size="15" />恢复待办</button>
          <button v-if="scheduleDetail.status === 'cancelled'" :disabled="actionBusy" @click="runScheduleAction('restore')"><RotateCcw :size="15" />恢复</button>
          <button v-if="scheduleDetail.status === 'pending'" class="danger" :disabled="actionBusy" @click="runScheduleAction('cancel')"><XCircle :size="15" />取消日程</button>
        </footer>
      </section>

      <section v-else-if="activeView.kind === 'task-detail' && teamTaskDetail" class="quick-detail-view">
        <header class="quick-detail-hero">
          <div><span class="quick-status-label">{{ statusLabel(teamTaskDetail.status) }}</span><h2>{{ teamTaskDetail.title }}</h2><p>{{ teamTaskDetail.teamName }} · {{ teamTaskDetail.groupName || '未分组' }}</p></div>
          <button v-if="canManageTeamTask" type="button" class="icon-button" title="编辑团队任务" aria-label="编辑团队任务" @click="openTaskEdit"><Edit3 :size="16" /></button>
        </header>
        <p v-if="teamTaskDetail.description" class="quick-detail-description">{{ teamTaskDetail.description }}</p>
        <dl class="quick-detail-list">
          <div><dt>创建者</dt><dd>{{ teamTaskDetail.creatorName }}</dd></div>
          <div><dt>开始时间</dt><dd>{{ formatTime(teamTaskDetail.startTime) }}</dd></div>
          <div><dt>截止时间</dt><dd>{{ formatTime(teamTaskDetail.deadlineTime) }}<small v-if="['active', 'unassigned'].includes(teamTaskDetail.status)">{{ countdown(teamTaskDetail.deadlineTime) }}</small></dd></div>
          <div v-if="teamTaskDetail.pendingReminders?.length"><dt>下次提醒</dt><dd>{{ formatTime(teamTaskDetail.pendingReminders[0].remindAt) }}</dd></div>
        </dl>

        <section v-if="teamTaskDetail.assignees?.length" class="quick-detail-section">
          <h3><UserRound :size="15" />执行人</h3>
          <div class="quick-assignee-list">
            <div v-for="assignee in teamTaskDetail.assignees" :key="assignee.assigneeId"><span>{{ assignee.nickname }}<small v-if="assignee.isCurrent">当前</small></span><em>{{ statusLabel(assignee.assignStatus) }}</em></div>
          </div>
        </section>

        <section v-if="teamTaskDetail.events?.length" class="quick-detail-section">
          <h3><Clock3 :size="15" />最近动态</h3>
          <div class="quick-event-list"><div v-for="event in teamTaskDetail.events.slice().reverse().slice(0, 5)" :key="event.id"><i></i><p><strong>{{ event.actorName || '系统' }}</strong>{{ event.content }}<small>{{ formatTime(event.createdAt) }}</small></p></div></div>
        </section>

        <footer class="quick-detail-actions">
          <button v-if="teamTaskDetail.canReview && teamTaskDetail.status === 'pending_approval'" class="primary" :disabled="actionBusy || hasTeamTaskVacancies" :title="hasTeamTaskVacancies ? '请先补齐执行人' : '批准安排'" @click="runTeamTaskAction('approve')"><Check :size="16" />批准安排</button>
          <button v-if="teamTaskDetail.canReview && teamTaskDetail.status === 'pending_approval'" :disabled="actionBusy" @click="runTeamTaskAction('reject-approval')"><XCircle :size="15" />拒绝审批</button>
          <button v-if="canManageTeamTask && teamTaskDetail.status === 'approval_rejected'" class="primary" :disabled="actionBusy" @click="runTeamTaskAction('resubmit-approval')"><RotateCcw :size="15" />重新提交审批</button>
          <button v-if="['active', 'unassigned'].includes(teamTaskDetail.status) && myTaskAssignment?.assignStatus === 'pending'" class="primary" :disabled="actionBusy" @click="runTeamTaskAction('accept')"><Check :size="16" />接受</button>
          <button v-if="['active', 'unassigned'].includes(teamTaskDetail.status) && myTaskAssignment?.assignStatus === 'accepted'" class="primary" :disabled="actionBusy" @click="runTeamTaskAction('complete')"><Check :size="16" />完成</button>
          <button v-if="['active', 'unassigned'].includes(teamTaskDetail.status) && ['pending', 'accepted'].includes(myTaskAssignment?.assignStatus || '')" :disabled="actionBusy" @click="runTeamTaskAction('reject')"><XCircle :size="15" />拒绝</button>
          <button v-if="canManageTeamTask && ['pending_approval', 'active', 'unassigned', 'all_rejected'].includes(teamTaskDetail.status)" class="danger" :disabled="actionBusy" @click="runTeamTaskAction('cancel')"><XCircle :size="15" />取消任务</button>
          <button v-if="canAdminTeamTask && teamTaskDetail.status === 'cancelled'" :disabled="actionBusy" @click="runTeamTaskAction('restore')"><RotateCcw :size="15" />恢复任务</button>
        </footer>
      </section>

      <div v-else class="quick-detail-loading"><span>未找到对应内容</span></div>
    </template>

    <div v-if="pendingCompleteTask" class="quick-modal-backdrop" role="presentation" @click.self="closeCompletionModal">
      <section class="quick-modal-panel quick-complete-modal" role="dialog" aria-modal="true" aria-labelledby="quick-complete-title">
        <header class="quick-modal-header">
          <div><p>团队任务</p><h2 id="quick-complete-title">记录完成时疲劳</h2></div>
          <button type="button" class="icon-button" title="关闭" aria-label="关闭" :disabled="actionBusy" @click="closeCompletionModal"><XCircle :size="17" /></button>
        </header>
        <p class="quick-modal-task-title">{{ pendingCompleteTask.title }}</p>
        <p class="quick-modal-hint">请选择你完成这项任务时的实际疲劳程度。该记录只计入完成当天。</p>
        <div class="quick-level-options fatigue quick-completion-levels" role="radiogroup" aria-label="完成疲劳程度">
          <button v-for="level in 5" :key="`quick-complete-fatigue-${level}`" type="button" :class="{ active: completingFatigueLevel === level }" role="radio" :aria-checked="completingFatigueLevel === level" :aria-label="`完成疲劳度 ${level} ${completionFatigueLabels[level - 1]}`" :disabled="actionBusy" @click="completingFatigueLevel = level"><strong>{{ level }}</strong><small>{{ completionFatigueLabels[level - 1] }}</small></button>
        </div>
        <footer class="quick-modal-actions">
          <button type="button" :disabled="actionBusy" @click="closeCompletionModal">取消</button>
          <button type="button" class="primary" :disabled="actionBusy || completingFatigueLevel === null" @click="confirmComplete"><Check :size="15" />{{ actionBusy ? '提交中...' : '确认完成' }}</button>
        </footer>
      </section>
    </div>
  </section>
</template>
