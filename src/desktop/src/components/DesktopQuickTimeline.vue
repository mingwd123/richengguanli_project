<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  CalendarClock,
  Check,
  ChevronRight,
  CircleDot,
  Clock3,
  Edit3,
  Flag,
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
  TaskForm,
  TaskGroup,
  TeamMember,
  TeamTask,
  TimeType,
  TimelinePresentationStatus,
  TimelineTimeRange,
} from '@web/types'
import { countdown, dateKeyInTimezone, formatTime, getDisplayTimezone, statusLabel, toApiTimePayload, toSchedulePayload } from '@web/utils/helpers'
import {
  buildTimelineStats,
  timelineOccursOnDate,
  timelinePresentationStatus,
  timelineTimeRange,
} from '@web/utils/timeline'
import { useDesktopShell } from '../stores/desktopShell'
import DesktopQuickScheduleEditor from './DesktopQuickScheduleEditor.vue'
import DesktopQuickTeamBreakdown, { type QuickTeamBreakdownTask } from './DesktopQuickTeamBreakdown.vue'
import DesktopQuickTeamTaskCreator from './DesktopQuickTeamTaskCreator.vue'
import DesktopQuickTaskEditor from './DesktopQuickTaskEditor.vue'
import type { QuickTaskEditForm } from './desktopQuickTypes'
import { isoToZonedDatetimeLocal, zonedDatetimeLocalToIso } from '../utils/timezone'
import { inferAiGroupId, normalizeAiDateTime, normalizeAiScheduleDraft, resolveAiTimeType } from '../utils/aiSchedule'

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

const store = useAppStore()
const shell = useDesktopShell()
const router = useRouter()
const workspaceRoot = ref<HTMLElement | null>(null)
const viewStack = ref<QuickView[]>([{ kind: 'timeline', scrollTop: 0 }])
const now = ref(Date.now())
const detailLoading = ref(false)
const formBusy = ref(false)
const actionBusy = ref(false)
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
const quickDataLoading = ref(false)
const quickDataLoaded = ref(false)
const quickSchedules = ref<Schedule[]>([])
const quickTasks = ref<MyTask[]>([])
const scheduleDetail = ref<Schedule | null>(null)
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
let quickDataRequest = 0
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
const todayKey = computed(() => dateKeyInTimezone(now.value, timezone.value))
const todayLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  timeZone: timezone.value,
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}).format(now.value))

const nestedViewTitle = computed(() => {
  if (activeView.value.kind === 'create') return '新建任务'
  if (activeView.value.kind === 'team-breakdown') return '确认 AI 子任务'
  if (activeView.value.kind === 'schedule-detail') return scheduleDetail.value?.title || '日程详情'
  if (activeView.value.kind === 'schedule-edit') return '编辑个人日程'
  if (activeView.value.kind === 'task-detail') return teamTaskDetail.value?.title || '团队任务详情'
  if (activeView.value.kind === 'task-edit') return '编辑团队任务'
  return '快捷时间轴'
})

const nestedViewKicker = computed(() => {
  if (activeView.value.kind === 'create') return createKind.value === 'schedule' ? '个人日程' : '团队任务'
  if (activeView.value.kind === 'team-breakdown') return '团队任务 · AI 拆解'
  if (activeView.value.kind.startsWith('schedule')) return '个人日程'
  if (activeView.value.kind.startsWith('task')) return '团队任务'
  return '今天'
})

function itemKey(sourceType: SourceType, item: RawTimelineItem) {
  return `${sourceType}-${item.id}`
}

function sourceItem(item: RawTimelineItem, sourceType: SourceType) {
  return { ...item, sourceType }
}

const allItems = computed(() => {
  const unique = new Map<string, { raw: RawTimelineItem; sourceType: SourceType }>()
  const add = (items: RawTimelineItem[], sourceType: SourceType) => {
    for (const item of items) unique.set(itemKey(sourceType, item), { raw: item, sourceType })
  }

  add(store.today.personalSchedules, 'schedule')
  add(store.today.teamTasks, 'team_task')
  add(quickDataLoaded.value ? quickSchedules.value : store.schedules, 'schedule')
  add(quickDataLoaded.value ? quickTasks.value : store.myTasks, 'team_task')
  return [...unique.values()]
})

const entries = computed<QuickEntry[]>(() => allItems.value.map(({ raw, sourceType }) => {
  const item = sourceItem(raw, sourceType)
  return {
    key: itemKey(sourceType, raw),
    sourceType,
    raw,
    range: timelineTimeRange(item),
    presentation: timelinePresentationStatus(item, now.value),
    sourceLabel: sourceType === 'schedule'
      ? ((raw as Schedule).groupName || '个人日程')
      : ((raw as MyTask).teamName || '团队任务'),
  }
}))

const openEntries = computed(() => entries.value.filter(entry =>
  !['completed', 'cancelled', 'rejected'].includes(entry.presentation)))

const overdueEntries = computed(() => openEntries.value
  .filter(entry => entry.presentation === 'overdue')
  .sort((left, right) => timeValue(left.range.endAt) - timeValue(right.range.endAt)))

const timelineEntries = computed(() => openEntries.value
  .filter(entry => entry.presentation !== 'overdue')
  .filter(entry => entry.range.sortAt && entry.presentation !== 'unscheduled')
  .filter(entry => timelineOccursOnDate(sourceItem(entry.raw, entry.sourceType), todayKey.value, timezone.value))
  .sort((left, right) => timeValue(left.range.sortAt) - timeValue(right.range.sortAt)))

const timelineRows = computed<QuickRow[]>(() => {
  const rows: QuickRow[] = timelineEntries.value.map(entry => ({ type: 'item', entry }))
  const firstUpcoming = rows.findIndex(row => row.type === 'item' && timeValue(row.entry.range.sortAt) >= now.value)
  rows.splice(firstUpcoming < 0 ? rows.length : firstUpcoming, 0, { type: 'now' })
  return rows
})

const unscheduledEntries = computed(() => openEntries.value
  .filter(entry => entry.presentation === 'unscheduled')
  .sort((left, right) => left.raw.title.localeCompare(right.raw.title, 'zh-CN')))

const stats = computed(() => buildTimelineStats(
  allItems.value.map(({ raw, sourceType }) => sourceItem(raw, sourceType)),
  { now: now.value, timezone: timezone.value },
))

const schedulePresentation = computed(() => scheduleDetail.value
  ? timelinePresentationStatus(sourceItem(scheduleDetail.value, 'schedule'), now.value)
  : 'unscheduled')

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

async function fetchAllPages<T>(path: string, params: Record<string, string>) {
  const items: T[] = []
  const size = 100
  let page = 1
  let total = Number.POSITIVE_INFINITY

  while (items.length < total) {
    const query = new URLSearchParams({ ...params, page: String(page), size: String(size) })
    const data = await store.request<PageResult<T>>(`${path}?${query.toString()}`)
    const pageItems = data.list || []
    items.push(...pageItems)
    total = Number(data.total || items.length)
    if (!pageItems.length || items.length >= total) break
    page += 1
  }
  return items
}

async function loadQuickTimelineData(notifyOnError = true) {
  const requestId = ++quickDataRequest
  quickDataLoading.value = true
  try {
    const [schedules, pendingTasks, acceptedTasks] = await Promise.all([
      fetchAllPages<Schedule>('/schedules', { status: 'pending', sort: 'time_asc' }),
      fetchAllPages<MyTask>('/team-tasks/my', { status: 'pending', sort: 'time_asc' }),
      fetchAllPages<MyTask>('/team-tasks/my', { status: 'accepted', sort: 'time_asc' }),
    ])
    if (requestId !== quickDataRequest) return
    const tasks = new Map<number, MyTask>()
    for (const task of [...pendingTasks, ...acceptedTasks]) tasks.set(task.id, task)
    quickSchedules.value = schedules
    quickTasks.value = [...tasks.values()]
    quickDataLoaded.value = true
  } catch (error: any) {
    if (requestId === quickDataRequest && notifyOnError) store.notify(error.message || '加载快捷时间轴失败')
  } finally {
    if (requestId === quickDataRequest) quickDataLoading.value = false
  }
}

async function refreshQuickWorkspace() {
  await Promise.all([store.loadAll(), loadQuickTimelineData()])
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
  if (entry.sourceType === 'schedule') return '完成'
  const task = entry.raw as MyTask
  if (task.assignStatus === 'pending') return '接受'
  if (task.assignStatus === 'accepted') return '完成'
  return ''
}

function canAct(entry: QuickEntry) {
  return Boolean(actionLabel(entry))
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

async function performAction(entry: QuickEntry) {
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    if (entry.sourceType === 'schedule') {
      await store.request(`/schedules/${entry.raw.id}/complete`, { method: 'PUT' })
      await refreshQuickWorkspace()
      return
    }
    const task = entry.raw as MyTask
    const action = task.assignStatus === 'pending' ? 'accept' : task.assignStatus === 'accepted' ? 'complete' : ''
    if (action) {
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
    await store.request('/team-tasks', { method: 'POST', body: JSON.stringify(payload) })
    teamCreateForm.value = newTeamTaskForm(teamId)
    await Promise.all([refreshQuickWorkspace(), loadTeamCreateContext(teamId)])
    store.notify('团队任务已创建')
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
        await store.request('/team-tasks', { method: 'POST', body: JSON.stringify(payload) })
        created += 1
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
      store.notify(created ? `已创建 ${created} 条，${failed.size} 条失败，请修正后重试` : '子任务创建失败，请检查提示后重试')
      return
    }

    const teamId = base.teamId
    teamCreateForm.value = newTeamTaskForm(teamId)
    await loadTeamCreateContext(teamId)
    aiBreakdownBase.value = null
    aiBreakdownTasks.value = []
    shell.setQuickTimelineExitGuard('none')
    clearTeamAiState()
    store.notify(`已创建 ${created} 条子任务`)
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
  }
  initialScheduleReminder.value = scheduleEditForm.value.remindAt
  await pushView({ kind: 'schedule-edit', id: item.id, scrollTop: 0 })
}

async function saveScheduleEdit() {
  if (!scheduleDetail.value) return
  const error = validateSchedule(scheduleEditForm.value)
  if (error) return store.notify(error)
  formBusy.value = true
  try {
    const payload = schedulePayload(scheduleEditForm.value)
    if (scheduleEditForm.value.remindAt === initialScheduleReminder.value) delete payload.remindAt
    else payload.remindAt = scheduleEditForm.value.remindAt
      ? zonedDatetimeLocalToIso(scheduleEditForm.value.remindAt, timezone.value)
      : ''
    await store.request(`/schedules/${scheduleDetail.value.id}`, { method: 'PUT', body: JSON.stringify(payload) })
    await refreshQuickWorkspace()
    store.notify('日程已更新')
    await loadScheduleDetail(scheduleDetail.value.id)
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
  let target = '/'
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
  }
  await router.push(target)
  await shell.setQuickTimeline(false)
}

function handleEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || viewStack.value.length <= 1 || aiBreakdownSaving.value) return
  event.preventDefault()
  goBack()
}

onMounted(() => {
  loadQuickTimelineData()
  timer = window.setInterval(() => { now.value = Date.now() }, 60_000)
  window.addEventListener('keydown', handleEscape)
})

onUnmounted(() => {
  shell.setQuickTimelineExitGuard('none')
  invalidateAiRequests()
  if (timer) window.clearInterval(timer)
  window.removeEventListener('keydown', handleEscape)
})
</script>

<template>
  <section ref="workspaceRoot" class="desktop-quick-timeline" aria-label="快捷任务工作区">
    <template v-if="activeView.kind === 'timeline'">
      <header class="quick-timeline-header">
        <div>
          <p>今天</p>
          <h1>{{ todayLabel }}</h1>
          <span>{{ stats.active ? `${stats.active} 项进行中` : `${stats.today} 项已安排` }}<b v-if="stats.overdue">{{ stats.overdue }} 项逾期</b></span>
        </div>
        <div class="quick-header-actions">
          <button type="button" class="icon-button" title="刷新数据" aria-label="刷新数据" :disabled="store.loading || quickDataLoading" @click="refreshQuickWorkspace"><RefreshCw :class="{ spinning: store.loading || quickDataLoading }" :size="17" /></button>
          <button type="button" class="icon-button" title="新建个人日程" aria-label="新建个人日程" @click="openCreateSchedule"><Plus :size="18" /></button>
          <button type="button" class="icon-button" title="打开完整工作台" aria-label="打开完整工作台" @click="openFullWorkspace"><PanelTopOpen :size="17" /></button>
        </div>
      </header>

      <section v-if="overdueEntries.length" class="quick-overdue-section" aria-label="已逾期任务">
        <div class="quick-section-heading"><Flag :size="15" /><span>已逾期</span><em>{{ overdueEntries.length }}</em></div>
        <article v-for="entry in overdueEntries" :key="entry.key" class="quick-overdue-item" @click="openEntry(entry)">
          <span class="quick-overdue-time">{{ formatClock(entry.range.endAt) }}</span>
          <div><strong>{{ entry.raw.title }}</strong><small>{{ entry.sourceLabel }}</small></div>
          <button v-if="canAct(entry)" type="button" class="quick-action" :title="actionLabel(entry)" :aria-label="actionLabel(entry)" @click.stop="performAction(entry)"><Check :size="15" /></button>
          <ChevronRight v-else :size="16" />
        </article>
      </section>

      <section class="quick-timeline-track" aria-label="今日时间轴">
        <div v-if="timelineRows.length === 1" class="quick-empty-state">
          <CalendarClock :size="25" />
          <strong>今天没有定时安排</strong>
          <button type="button" @click="openCreateSchedule">新建日程</button>
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
                <button v-if="canAct(row.entry)" type="button" class="quick-card-action" :title="actionLabel(row.entry)" :aria-label="actionLabel(row.entry)" @click="performAction(row.entry)"><Check :size="15" /></button>
              </div>
            </template>
          </article>
        </template>
      </section>

      <section v-if="unscheduledEntries.length" class="quick-unscheduled-section" aria-label="待安排任务">
        <div class="quick-section-heading"><ListTodo :size="15" /><span>待安排</span><em>{{ unscheduledEntries.length }}</em></div>
        <article v-for="entry in unscheduledEntries" :key="entry.key" class="quick-unscheduled-item" @click="openEntry(entry)">
          <div><strong>{{ entry.raw.title }}</strong><small>{{ entry.sourceLabel }}</small></div>
          <button v-if="canAct(entry)" type="button" class="quick-action" :title="actionLabel(entry)" :aria-label="actionLabel(entry)" @click.stop="performAction(entry)"><Check :size="15" /></button>
          <ChevronRight v-else :size="16" />
        </article>
      </section>
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
      <DesktopQuickScheduleEditor v-else-if="activeView.kind === 'schedule-edit'" v-model="scheduleEditForm" :groups="store.taskGroups" :busy="formBusy" :reminder-presets="store.notificationPreferences.reminderPresetMinutes" :timezone="timezone" submit-label="保存修改" @error="store.notify" @submit="saveScheduleEdit" />
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
        <footer class="quick-detail-actions">
          <button v-if="scheduleDetail.status === 'pending'" class="primary" :disabled="actionBusy" @click="runScheduleAction('complete')"><Check :size="16" />完成</button>
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
          <div><dt>截止时间</dt><dd>{{ formatTime(teamTaskDetail.deadlineTime) }}<small v-if="teamTaskDetail.status === 'active'">{{ countdown(teamTaskDetail.deadlineTime) }}</small></dd></div>
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
          <button v-if="myTaskAssignment?.assignStatus === 'pending'" class="primary" :disabled="actionBusy" @click="runTeamTaskAction('accept')"><Check :size="16" />接受</button>
          <button v-if="myTaskAssignment?.assignStatus === 'accepted'" class="primary" :disabled="actionBusy" @click="runTeamTaskAction('complete')"><Check :size="16" />完成</button>
          <button v-if="['pending', 'accepted'].includes(myTaskAssignment?.assignStatus || '')" :disabled="actionBusy" @click="runTeamTaskAction('reject')"><XCircle :size="15" />拒绝</button>
          <button v-if="canManageTeamTask && teamTaskDetail.status === 'active'" class="danger" :disabled="actionBusy" @click="runTeamTaskAction('cancel')"><XCircle :size="15" />取消任务</button>
          <button v-if="canAdminTeamTask && teamTaskDetail.status === 'cancelled'" :disabled="actionBusy" @click="runTeamTaskAction('restore')"><RotateCcw :size="15" />恢复任务</button>
        </footer>
      </section>

      <div v-else class="quick-detail-loading"><span>未找到对应内容</span></div>
    </template>
  </section>
</template>
