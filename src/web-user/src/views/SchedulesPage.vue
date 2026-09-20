<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import ContextMenu from '../components/ContextMenu.vue'
import PaginationBar from '../components/PaginationBar.vue'
import CountdownPill from '../components/CountdownPill.vue'
import FatiguePreviewInline from '../components/FatiguePreviewInline.vue'
import ScheduleLevelControl from '../components/ScheduleLevelControl.vue'
import ReminderShortcutPicker from '../components/ReminderShortcutPicker.vue'
import RepeatRuleEditor from '../components/RepeatRuleEditor.vue'
import { AlertTriangle, BatteryMedium, Clock3, Layers3, MoreHorizontal } from 'lucide-vue-next'
import { formatTime, getDisplayTimezone, primaryTime, timeTypeLabel, statusLabel, isOverdue, toDatetimeLocalInTimezone, toSchedulePayload, zonedDateTimeToIso, formatProgressPercent, canTrackDailyProgress } from '../utils/helpers'
import type { FatiguePreview, Schedule, ScheduleForm, ScheduleViewMode } from '../types'

const router = useRouter()
const route = useRoute()
const store = useAppStore()
const collapsedGroups = ref<string[]>([])
const aiParsing = ref(false)
const aiParseError = ref('')
const showDraftConfirm = ref(false)
const aiDraft = ref<{ title: string; groupId: string; timeType: string; startTime: string; endTime: string; deadlineTime: string; remindAt: string; description: string; urgencyLevel: number; fatigueLevel: number }>({ title: '', groupId: '', timeType: 'point_event', startTime: '', endTime: '', deadlineTime: '', remindAt: '', description: '', urgencyLevel: 3, fatigueLevel: 3 })
const contextMenu = ref<{ x: number; y: number; type: 'group' | 'schedule'; group?: any; schedule?: Schedule } | null>(null)
// 每日进度只适用于任务类型且与重复规则互斥；不满足时开关置灰并说明原因。
const canTrackProgress = computed(() => canTrackDailyProgress(store.scheduleForm))

watch(canTrackProgress, (value) => {
  if (!value) store.scheduleForm.progressTrackingEnabled = false
})
const filterKeyword = ref(store.schedulePage.keyword || '')
const filterStatus = ref(store.schedulePage.status || 'pending')
const filterDateFrom = ref(store.schedulePage.dateFrom || '')
const filterDateTo = ref(store.schedulePage.dateTo || '')
const scheduleStatus = computed(() => (filterStatus.value || 'pending') as 'pending' | 'completed' | 'cancelled')
const draggingGroupId = ref<number | null>(null)
const draggingSchedule = ref<{ id: number; fromGroupId: number | null } | null>(null)
const createFatiguePreview = ref<FatiguePreview | null>(null)
const draftFatiguePreview = ref<FatiguePreview | null>(null)
const reminderPresets = computed(() => store.notificationPreferences.reminderPresetMinutes || [])
const userTimezone = computed(() => store.profile?.timezone || getDisplayTimezone())
const aiReminderBaseTime = computed(() => aiDraft.value.timeType === 'deadline_task' ? aiDraft.value.deadlineTime : aiDraft.value.startTime)
const aiReminderBaseLabel = computed(() => aiDraft.value.timeType === 'deadline_task' ? '截止时间' : '开始时间')
const isGroupedView = computed(() => store.viewMode === 'group')
const canReorder = computed(() => isGroupedView.value && scheduleStatus.value === 'pending')
const levelOptions = [1, 2, 3, 4, 5]
const urgencyNames = ['不紧急', '较低', '普通', '紧急', '非常紧急']
const fatigueNames = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']
const viewOptions = [
  { value: 'time' as ScheduleViewMode, label: '时间', icon: Clock3 },
  { value: 'group' as ScheduleViewMode, label: '分组', icon: Layers3 },
  { value: 'urgency' as ScheduleViewMode, label: '紧急度', icon: AlertTriangle },
  { value: 'fatigue' as ScheduleViewMode, label: '疲劳度', icon: BatteryMedium },
]
const statusOptions = [
  { value: 'pending' as const, label: '待处理' },
  { value: 'completed' as const, label: '已完成' },
  { value: 'cancelled' as const, label: '已取消' },
]
const reminderBaseTime = computed(() => {
  return store.scheduleForm.timeType === 'deadline_task'
    ? store.scheduleForm.deadlineTime
    : store.scheduleForm.startTime
})

function reminderBaseLabel() {
  return store.scheduleForm.timeType === 'deadline_task' ? '截止时间' : '开始时间'
}

function toDatetimeLocal(value: string) {
  return toDatetimeLocalInTimezone(value, userTimezone.value)
}

function inferGroupId(draft: any, sourceText: string) {
  const explicitGroupName = sourceText.match(/(?:放到?|归到|归入|分到|放进|放入)([^，。,.\s]+)/)?.[1]?.trim()
  const matchedByExplicit = explicitGroupName && store.taskGroups.find(group => group.name === explicitGroupName)
  if (matchedByExplicit) return String(matchedByExplicit.id)
  const groupName = String(draft.groupName || '').trim()
  const matchedByAi = groupName && store.taskGroups.find(group => group.name === groupName)
  if (matchedByAi) return String(matchedByAi.id)
  const text = `${sourceText} ${draft.title || ''}`
  const keywordRules = [
    { name: '学习', keywords: ['学习', '图书馆', '读书', '上课', '考试', '复习', '作业'] },
    { name: '工作', keywords: ['工作', '开会', '会议', '项目', '汇报', '客户'] },
    { name: '运动', keywords: ['运动', '健身', '跑步', '游泳', '训练'] },
    { name: '生活', keywords: ['生活', '吃饭', '购物', '家务', '买菜'] }
  ]
  for (const rule of keywordRules) {
    if (rule.keywords.some(keyword => text.includes(keyword))) {
      const group = store.taskGroups.find(item => item.name === rule.name)
      if (group) return String(group.id)
    }
  }
  return ''
}

async function aiParseSchedule() {
  if (!store.scheduleForm.title.trim()) { store.notify('请先输入标题再使用 AI 解析'); return }
  aiParsing.value = true; aiParseError.value = ''
  try {
    const sourceText = store.scheduleForm.title.trim()
    const result = await store.aiRequest('/schedules/parse', { text: sourceText })
    const draft = result.draft || {}
    const groupId = inferGroupId(draft, sourceText)
    const nextTimeType = draft.startTime && draft.endTime
      ? 'duration_task'
      : draft.deadlineTime
        ? 'deadline_task'
        : ['point_event', 'deadline_task', 'duration_task'].includes(draft.timeType)
          ? draft.timeType
          : store.scheduleForm.timeType
    aiDraft.value = {
      title: draft.title || store.scheduleForm.title,
      groupId,
      timeType: nextTimeType,
      startTime: draft.startTime ? toDatetimeLocal(draft.startTime) : '',
      endTime: draft.endTime ? toDatetimeLocal(draft.endTime) : '',
      deadlineTime: draft.deadlineTime ? toDatetimeLocal(draft.deadlineTime) : '',
      remindAt: draft.remindAt ? toDatetimeLocal(draft.remindAt) : store.scheduleForm.remindAt,
      description: draft.description || '',
      urgencyLevel: store.scheduleForm.urgencyLevel,
      fatigueLevel: store.scheduleForm.fatigueLevel
    }
    showDraftConfirm.value = true
    store.scheduleModalOpen = false
  } catch (e: any) {
    aiParseError.value = 'AI 解析失败: ' + (e.message || '服务不可用')
  } finally { aiParsing.value = false }
}
async function confirmAiDraft() {
  const draft = aiDraft.value
  store.scheduleForm.title = draft.title
  store.scheduleForm.groupId = draft.groupId
  store.scheduleForm.timeType = draft.timeType as any
  store.scheduleForm.startTime = draft.startTime
  store.scheduleForm.endTime = draft.endTime
  store.scheduleForm.deadlineTime = draft.deadlineTime
  store.scheduleForm.description = draft.description
  store.scheduleForm.remindAt = draft.remindAt
  store.scheduleForm.urgencyLevel = draft.urgencyLevel
  store.scheduleForm.fatigueLevel = draft.fatigueLevel
  showDraftConfirm.value = false
  await store.createSchedule()
}
function cancelAiDraft() {
  showDraftConfirm.value = false
  aiParseError.value = ''
}

const filteredSchedules = computed(() => {
  return store.schedules
})

let filterTimer: ReturnType<typeof setTimeout> | undefined
watch([filterKeyword, filterDateFrom, filterDateTo, () => store.urgencyLevelFilter, () => store.fatigueLevelFilter], () => {
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => store.loadSchedules({
    page: 1,
    keyword: filterKeyword.value,
    status: filterStatus.value,
    dateFrom: filterDateFrom.value,
    dateTo: filterDateTo.value
  }), 250)
})
watch(() => store.schedulePage.sort, () => {
  if (isGroupedView.value) store.loadSchedules({ page: 1 })
})
watch(() => store.scheduleModalOpen, isOpen => {
  if (!isOpen) createFatiguePreview.value = null
})

let createPreviewTimer: ReturnType<typeof setTimeout> | undefined
let draftPreviewTimer: ReturnType<typeof setTimeout> | undefined
let createPreviewVersion = 0
let draftPreviewVersion = 0

function hasPreviewTime(form: Pick<ScheduleForm, 'timeType' | 'startTime' | 'deadlineTime'>) {
  return form.timeType === 'deadline_task' ? Boolean(form.deadlineTime) : Boolean(form.startTime)
}

async function loadCreateFatiguePreview() {
  const version = ++createPreviewVersion
  if (!store.scheduleModalOpen || !hasPreviewTime(store.scheduleForm) || store.fatigueProfile?.fatigueTrackingEnabled === false) {
    createFatiguePreview.value = null
    return
  }
  try {
    const payload = toSchedulePayload(store.scheduleForm, userTimezone.value)
    const result = await store.previewFatigue({ operation: 'create', ...payload })
    if (version === createPreviewVersion) createFatiguePreview.value = result
  } catch {
    if (version === createPreviewVersion) createFatiguePreview.value = null
  }
}

async function loadDraftFatiguePreview() {
  const version = ++draftPreviewVersion
  const draft = aiDraft.value
  if (!showDraftConfirm.value || !hasPreviewTime(draft as ScheduleForm) || store.fatigueProfile?.fatigueTrackingEnabled === false) {
    draftFatiguePreview.value = null
    return
  }
  try {
    const payload = toSchedulePayload({ ...draft, groupName: '' } as ScheduleForm, userTimezone.value)
    const result = await store.previewFatigue({ operation: 'create', ...payload })
    if (version === draftPreviewVersion) draftFatiguePreview.value = result
  } catch {
    if (version === draftPreviewVersion) draftFatiguePreview.value = null
  }
}

watch([
  () => store.scheduleModalOpen,
  () => store.scheduleForm.timeType,
  () => store.scheduleForm.startTime,
  () => store.scheduleForm.endTime,
  () => store.scheduleForm.deadlineTime,
  () => store.scheduleForm.fatigueLevel,
], () => {
  clearTimeout(createPreviewTimer)
  createPreviewTimer = setTimeout(loadCreateFatiguePreview, 250)
})

watch([
  showDraftConfirm,
  () => aiDraft.value.timeType,
  () => aiDraft.value.startTime,
  () => aiDraft.value.endTime,
  () => aiDraft.value.deadlineTime,
  () => aiDraft.value.fatigueLevel,
], () => {
  clearTimeout(draftPreviewTimer)
  draftPreviewTimer = setTimeout(loadDraftFatiguePreview, 250)
})

onUnmounted(() => {
  clearTimeout(filterTimer)
  clearTimeout(createPreviewTimer)
  clearTimeout(draftPreviewTimer)
})

function validView(value: unknown): value is ScheduleViewMode {
  return ['time', 'group', 'urgency', 'fatigue'].includes(String(value))
}

async function applyView(mode: ScheduleViewMode) {
  if (store.viewMode !== mode) await store.setScheduleViewMode(mode)
  await router.replace({ query: { ...route.query, view: mode } })
}

async function applyStatus(status: 'pending' | 'completed' | 'cancelled') {
  filterStatus.value = status
  await store.loadSchedules({ page: 1, status })
  await router.replace({ query: { ...route.query, view: store.viewMode, status } })
}

onMounted(async () => {
  const queryView = route.query.view
  const queryStatus = route.query.status
  if (['pending', 'completed', 'cancelled'].includes(String(queryStatus))) filterStatus.value = String(queryStatus) as 'pending' | 'completed' | 'cancelled'
  else filterStatus.value = 'pending'
  if (validView(queryView) && queryView !== store.viewMode) await store.setScheduleViewMode(queryView)
  await store.loadSchedules({ page: 1, status: filterStatus.value })
  if (!validView(queryView) || queryStatus !== filterStatus.value) await router.replace({ query: { ...route.query, view: store.viewMode, status: filterStatus.value } })
})

const scheduleGroups = computed(() => {
  const active = filteredSchedules.value
  const groups = store.taskGroups.map(group => ({
    id: group.id,
    name: group.name,
    sortOrder: group.sortOrder,
    items: active.filter(schedule => schedule.groupId === group.id).sort(scheduleOrder)
  }))
  const groupedIds = new Set(store.taskGroups.map(group => group.id))
  const ungrouped = active.filter(schedule => !groupedIds.has(schedule.groupId || 0)).sort(scheduleOrder)
  if (ungrouped.length) groups.push({ id: 0, name: '未分组', sortOrder: Number.MAX_SAFE_INTEGER - 1, items: ungrouped })
  return groups.sort((a, b) => a.sortOrder - b.sortOrder)
})

function scheduleOrder(a: Schedule, b: Schedule) {
  if (scheduleStatus.value === 'completed') {
    const completedDelta = new Date(b.completedAt || 0).getTime() - new Date(a.completedAt || 0).getTime()
    return completedDelta || b.id - a.id
  }
  return Number(isOverdue(b)) - Number(isOverdue(a)) || a.sortOrder - b.sortOrder || a.id - b.id
}

const serverSections = computed(() => {
  const rows = new Map<string, { id: string; name: string; sortOrder: number; items: Schedule[]; plannedLoad: number; completedLoad: number; pendingCount: number; completedCount: number; total: number }>()
  if (store.viewMode === 'urgency' || store.viewMode === 'fatigue') {
    store.sectionSummaries.forEach((summary, index) => {
      rows.set(summary.key, {
        id: summary.key,
        name: summary.label,
        sortOrder: index,
        items: [],
        plannedLoad: Number(summary.plannedLoad || 0),
        completedLoad: Number(summary.completedLoad || 0),
        pendingCount: Number(summary.pendingCount || 0),
        completedCount: Number(summary.completedCount || 0),
        total: Number(summary.total || 0),
      })
    })
  }
  for (const schedule of filteredSchedules.value) {
    const id = schedule.sectionKey || `${store.viewMode}:${schedule.sectionLabel || '未分组'}`
    const seeded = rows.has(id)
    const current = rows.get(id) || { id, name: schedule.sectionLabel || '未分组', sortOrder: rows.size, items: [], plannedLoad: 0, completedLoad: 0, pendingCount: 0, completedCount: 0, total: 0 }
    current.items.push(schedule)
    if (!seeded) {
      current.total += 1
      if (schedule.status === 'pending') current.pendingCount += 1
      if (schedule.status === 'pending') current.plannedLoad += Number(schedule.fatigueWeight || 3)
      if (schedule.status === 'completed') { current.completedCount += 1; current.completedLoad += Number(schedule.completedFatigueWeight || schedule.fatigueWeight || 3) }
    }
    rows.set(id, current)
  }
  return [...rows.values()].map(section => {
    const summary = store.sectionSummaries.find(item => item.key === section.id)
    return summary ? { ...section, ...summary, name: summary.label, items: section.items } : section
  })
})

const displaySections = computed(() => isGroupedView.value ? scheduleGroups.value : serverSections.value)

function sectionSummary(section: any) {
  return store.sectionSummaries.find(item => item.key === section.id || item.key === section.sectionKey)
}

function sectionCount(section: any) {
  return Number(section.total ?? section.items?.length ?? 0)
}

function sectionLoadText(section: any) {
  if (scheduleStatus.value === 'completed') return `完成负荷 ${Number(section.completedLoad || 0)}`
  if (scheduleStatus.value === 'pending') return `计划负荷 ${Number(section.plannedLoad || 0)}`
  return ''
}

function scheduleOverdue(schedule: Schedule) {
  return Boolean(schedule.isOverdue ?? isOverdue(schedule))
}

function levelLabel(level: number, labels: string[]) {
  return labels[Math.max(0, Math.min(labels.length - 1, Number(level || 3) - 1))]
}

function toggleGroup(name: string) {
  collapsedGroups.value = collapsedGroups.value.includes(name)
    ? collapsedGroups.value.filter(value => value !== name)
    : [...collapsedGroups.value, name]
}
function goDetail(id: number) { router.push(`/schedules/${id}`) }
function handleAction(item: Schedule, action: string) { store.setScheduleStatus(item, action) }
/** P04：每日进度任务不能走普通完成接口，统一跳到详情页走进度提交流程。 */
function handleComplete(item: Schedule) {
  if (item.progressTrackingEnabled) {
    store.notify('该任务启用了每日进度，请到详情页提交进度')
    goDetail(item.id)
    return
  }
  handleAction(item, 'complete')
}
function handleDelete(id: number) { store.deleteSchedule(id) }
function moveGroup(groupId: number, direction: -1 | 1) {
  const ids = store.taskGroups.map(group => group.id)
  const index = ids.indexOf(groupId)
  const target = index + direction
  if (index < 0 || target < 0 || target >= ids.length) return
  ;[ids[index], ids[target]] = [ids[target], ids[index]]
  store.sortTaskGroups(ids)
}
function renameGroup(groupId: number, name: string) {
  const nextName = window.prompt('请输入分组名称', name)
  if (!nextName?.trim()) return
  const group = store.taskGroups.find(item => item.id === groupId)
  if (group) store.updateTaskGroup({ ...group, name: nextName.trim() })
}
function deleteGroup(groupId: number, name: string) {
  const group = store.taskGroups.find(item => item.id === groupId)
  if (group && window.confirm(`确定删除分组「${name}」吗？其中日程会移动到其他分组。`)) store.deleteTaskGroup(group)
}
function moveSchedule(schedule: Schedule, event: Event) {
  const groupId = (event.target as HTMLSelectElement).value
  if (groupId && Number(groupId) !== schedule.groupId) store.moveScheduleGroup(schedule.id, groupId)
}
function moveScheduleOrder(groupId: number, items: Schedule[], scheduleId: number, direction: -1 | 1) {
  const ids = items.map(item => item.id)
  const index = ids.indexOf(scheduleId)
  const target = index + direction
  if (groupId === 0 || target < 0 || target >= ids.length) return
  ;[ids[index], ids[target]] = [ids[target], ids[index]]
  if (groupId === -1) store.sortCompletedSchedules(ids)
  else store.sortSchedules(groupId, ids)
}
async function createScheduleWithShortcut() {
  const match = store.scheduleForm.title.trim().match(/^\/([^\s/]+)\s+(.+)$/)
  if (match) {
    let group = store.taskGroups.find(item => item.name === match[1])
    if (!group) group = await store.createTaskGroupByName(match[1])
    if (group) {
      store.scheduleForm.groupId = String(group.id)
      store.scheduleForm.title = match[2]
    }
  }
  await store.createSchedule()
}
function openGroupMenu(event: MouseEvent, group: any) {
  if (group.id <= 0) return
  contextMenu.value = { x: event.clientX, y: event.clientY, type: 'group', group }
}
function openGroupButtonMenu(event: MouseEvent, group: any) {
  if (group.id <= 0) return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  contextMenu.value = { x: Math.max(8, rect.right - 148), y: rect.bottom + 4, type: 'group', group }
}
function openScheduleMenu(event: MouseEvent, group: any, schedule: Schedule) {
  contextMenu.value = { x: event.clientX, y: event.clientY, type: 'schedule', group, schedule }
}
function openScheduleButtonMenu(event: MouseEvent, group: any, schedule: Schedule) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  contextMenu.value = { x: Math.max(8, rect.right - 148), y: rect.bottom + 4, type: 'schedule', group, schedule }
}
function selectContextAction(action: string) {
  const menu = contextMenu.value
  contextMenu.value = null
  if (!menu) return
  if (menu.type === 'group') {
    if (action === 'rename') renameGroup(menu.group.id, menu.group.name)
    if (action === 'delete') deleteGroup(menu.group.id, menu.group.name)
    if (action === 'up') moveGroup(menu.group.id, -1)
    if (action === 'down') moveGroup(menu.group.id, 1)
    if (action === 'toggle') toggleGroup(menu.group.name)
  } else if (menu.schedule) {
    if (action === 'detail') goDetail(menu.schedule.id)
    if (action === 'complete') handleComplete(menu.schedule)
    if (action === 'uncomplete') handleAction(menu.schedule, 'uncomplete')
    if (action === 'cancel') handleAction(menu.schedule, 'cancel')
    if (action === 'restore') handleAction(menu.schedule, 'restore')
    if (action === 'delete') handleDelete(menu.schedule.id)
    if (action === 'edit-time') editScheduleTime(menu.schedule)
    if (action === 'remove-time') removeScheduleDeadline(menu.schedule)
    if (action === 'move-group') moveScheduleToGroup(menu.schedule)
  }
}
function dropGroup(targetGroupId: number) {
  if (!draggingGroupId.value || targetGroupId <= 0 || draggingGroupId.value === targetGroupId) return
  const ids = store.taskGroups.map(group => group.id)
  const from = ids.indexOf(draggingGroupId.value)
  const to = ids.indexOf(targetGroupId)
  if (from < 0 || to < 0) return
  ids.splice(to, 0, ids.splice(from, 1)[0])
  store.sortTaskGroups(ids)
  draggingGroupId.value = null
}
function dropSchedule(group: any, targetId?: number) {
  const drag = draggingSchedule.value
  draggingSchedule.value = null
  if (!drag || group.id === 0) return
  if (group.id === -1) {
    const item = filteredSchedules.value.find(schedule => schedule.id === drag.id)
    if (!item || item.status !== 'completed') return
    const ids = group.items.map((schedule: Schedule) => schedule.id)
    const from = ids.indexOf(drag.id)
    const to = targetId ? ids.indexOf(targetId) : ids.length - 1
    if (from < 0 || to < 0 || from === to) return
    ids.splice(to, 0, ids.splice(from, 1)[0])
    store.sortCompletedSchedules(ids)
    return
  }
  if (drag.fromGroupId !== group.id) {
    store.moveScheduleGroup(drag.id, group.id)
    return
  }
  const ids = group.items.map((item: Schedule) => item.id)
  const from = ids.indexOf(drag.id)
  const to = targetId ? ids.indexOf(targetId) : ids.length - 1
  if (from < 0 || to < 0 || from === to) return
  ids.splice(to, 0, ids.splice(from, 1)[0])
  store.sortSchedules(group.id, ids)
}
async function editScheduleTime(schedule: Schedule) {
  const current = schedule.deadlineTime || schedule.endTime || schedule.startTime || ''
  const userInput = window.prompt('请输入新时间 (格式: yyyy-MM-dd HH:mm)', toDatetimeLocal(current).replace('T', ' '))
  if (userInput === null || !userInput.trim()) return
  try {
    const iso = zonedDateTimeToIso(userInput.trim(), userTimezone.value)
    const payload: any = {}
    if (schedule.timeType === 'deadline_task') payload.deadlineTime = iso
    else if (schedule.timeType === 'duration_task') payload.endTime = iso
    else payload.startTime = iso
    await store.request(`/schedules/${schedule.id}`, { method: 'PUT', body: JSON.stringify(payload) })
    await store.loadAll()
    store.notify('时间已更新')
  } catch (e: any) { store.notify(e.message || '更新失败') }
}
async function removeScheduleDeadline(schedule: Schedule) {
  if (!window.confirm('确定移除时间吗？')) return
  try {
    const payload: any = {}
    if (schedule.timeType === 'deadline_task') payload.deadlineTime = ''
    else if (schedule.timeType === 'duration_task') payload.endTime = ''
    else payload.startTime = ''
    await store.request(`/schedules/${schedule.id}`, { method: 'PUT', body: JSON.stringify(payload) })
    await store.loadAll()
    store.notify('时间已移除')
  } catch (e: any) { store.notify(e.message || '移除失败') }
}
async function moveScheduleToGroup(schedule: Schedule) {
  const groupName = window.prompt('输入目标模块名称\n可选: ' + store.taskGroups.map(g => g.name).join(', '), schedule.groupName || '')
  if (!groupName?.trim()) return
  const group = store.taskGroups.find(g => g.name === groupName.trim())
  if (!group) { store.notify('未找到模块: ' + groupName.trim()); return }
  store.moveScheduleGroup(schedule.id, group.id)
}
const contextItems = computed(() => {
  const menu = contextMenu.value
  if (!menu) return []
  if (menu.type === 'group') return [
    { label: '折叠/展开', action: 'toggle' },
    { label: '重命名', action: 'rename' },
    { label: '上移', action: 'up' },
    { label: '下移', action: 'down' },
    { label: '删除', action: 'delete' }
  ]
  const schedule = menu.schedule!
  return [
    { label: '查看详情', action: 'detail' },
    { label: schedule.progressTrackingEnabled ? '提交进度' : '完成', action: 'complete', disabled: schedule.status !== 'pending' },
    { label: '恢复', action: 'uncomplete', disabled: schedule.status !== 'completed' },
    { label: '取消', action: 'cancel', disabled: schedule.status !== 'pending' },
    { label: '恢复已取消', action: 'restore', disabled: schedule.status !== 'cancelled' },
    { separator: true as any, label: '' },
    { label: '修改截止时间', action: 'edit-time', disabled: schedule.status !== 'pending' },
    { label: '移除时间', action: 'remove-time', disabled: schedule.status !== 'pending' },
    { label: '移动至模块', action: 'move-group', disabled: schedule.status !== 'pending' },
    { separator: true as any, label: '' },
    { label: '删除', action: 'delete' }
  ]
})
</script>

<template>
  <section class="list-page">
    <div class="search-bar">
      <input v-model="filterKeyword" placeholder="搜索标题或模块..." class="search-input" />
      <div class="segmented-control status-switch" aria-label="日程状态">
        <button v-for="option in statusOptions" :key="option.value" type="button" :class="{ active: scheduleStatus === option.value }" :aria-pressed="scheduleStatus === option.value" @click="applyStatus(option.value)">{{ option.label }} {{ store.scheduleStatusCounts[option.value] ?? 0 }}</button>
      </div>
      <input v-model="filterDateFrom" type="date" title="开始日期" />
      <input v-model="filterDateTo" type="date" title="截止日期" />
      <select v-model="store.urgencyLevelFilter" aria-label="紧急度筛选">
        <option :value="null">全部紧急度</option><option v-for="level in levelOptions" :key="`urgency-${level}`" :value="level">紧急度 {{ level }} · {{ levelLabel(level, urgencyNames) }}</option>
      </select>
      <select v-model="store.fatigueLevelFilter" aria-label="疲劳度筛选">
        <option :value="null">全部疲劳度</option><option v-for="level in levelOptions" :key="`fatigue-${level}`" :value="level">疲劳度 {{ level }} · {{ levelLabel(level, fatigueNames) }}</option>
      </select>
      <button v-if="filterKeyword || filterDateFrom || filterDateTo || store.urgencyLevelFilter || store.fatigueLevelFilter" class="plain-button" @click="filterKeyword='';filterDateFrom='';filterDateTo='';store.urgencyLevelFilter=null;store.fatigueLevelFilter=null" style="color:#e11d48">清除</button>
      <button class="primary" @click="store.openScheduleModal()">新建日程</button>
    </div>
    <div class="schedule-view-toolbar">
      <div class="segmented-control" aria-label="日程视图">
        <button v-for="option in viewOptions" :key="option.value" type="button" :class="{ active: store.viewMode === option.value }" :aria-pressed="store.viewMode === option.value" @click="applyView(option.value)">
          <component :is="option.icon" :size="15" />{{ option.label }}
        </button>
      </div>
      <span class="muted">{{ scheduleStatus === 'completed' ? `${store.schedulePage.total} 项已完成 · 最近完成优先` : scheduleStatus === 'cancelled' ? `${store.schedulePage.total} 项已取消` : `${store.schedulePage.total} 项待处理 · 服务端已按当前视图排序` }}</span>
    </div>
    <section class="table-card">
      <section
        v-for="group in displaySections"
        :key="group.id"
        class="group-block"
        :class="{ 'readonly-section': !canReorder }"
        @dragover.prevent="canReorder"
        @drop="canReorder ? dropSchedule(group) : undefined"
      >
        <div
          class="group-title"
          :draggable="canReorder && group.id > 0"
          @dragstart="canReorder ? draggingGroupId = group.id : undefined"
          @drop.stop.prevent="canReorder ? dropGroup(group.id) : undefined"
          @contextmenu.prevent="isGroupedView ? openGroupMenu($event, group) : undefined"
        >
          <button class="plain-button" @click="toggleGroup(group.name)">
            <span>{{ collapsedGroups.includes(group.name) ? '▸' : '▾' }} {{ group.name }}</span>
          </button>
          <div class="group-actions" @click.stop>
            <span v-if="sectionLoadText(group)" class="section-load">{{ sectionLoadText(group) }}</span>
            <em>{{ sectionCount(group) }}</em>
            <template v-if="isGroupedView && group.id > 0">
              <button class="icon-button row-menu-button mobile-only" title="更多分组操作" aria-label="更多分组操作" @click="openGroupButtonMenu($event, group)"><MoreHorizontal :size="18" /></button>
              <button title="上移" @click="moveGroup(group.id, -1)">上移</button>
              <button title="下移" @click="moveGroup(group.id, 1)">下移</button>
              <button @click="renameGroup(group.id, group.name)">重命名</button>
              <button @click="deleteGroup(group.id, group.name)">删除</button>
            </template>
          </div>
        </div>
        <div v-if="!collapsedGroups.includes(group.name) && group.items.length">
          <article
            v-for="(s, index) in group.items"
            :key="s.id"
            :class="['table-row', { overdue: scheduleOverdue(s), completed: s.status === 'completed' }]"
            style="cursor:pointer"
            @click="goDetail(s.id)"
            :draggable="canReorder"
            @dragstart="canReorder ? draggingSchedule = { id: s.id, fromGroupId: s.groupId } : undefined"
            @dragover.prevent="canReorder"
            @drop.stop.prevent="canReorder ? dropSchedule(group, s.id) : undefined"
            @contextmenu.prevent="isGroupedView ? openScheduleMenu($event, group, s) : undefined"
          >
            <div>
              <strong>{{ s.title }}</strong>
              <small>{{ s.groupName }} - {{ timeTypeLabel(s.timeType) }}</small>
              <div class="schedule-levels">
                <span class="level-chip urgency"><AlertTriangle :size="12" />紧急 {{ s.urgencyLevel }} · {{ levelLabel(s.urgencyLevel, urgencyNames) }}</span>
                <span class="level-chip fatigue"><BatteryMedium :size="12" />疲劳 {{ s.fatigueLevel }} · {{ levelLabel(s.fatigueLevel, fatigueNames) }} · {{ s.fatigueWeight }} 点</span>
              </div>
              <small v-if="s.status === 'completed' && s.completedFatigueLevel" class="muted">完成快照：疲劳 {{ s.completedFatigueLevel }} · {{ s.completedFatigueWeight }} 点</small>
              <small v-else-if="s.progressTrackingEnabled" class="muted">
                每日进度 {{ formatProgressPercent(s.progressPercent) }}<template v-if="s.status === 'completed'"> · 达 100% 完成（负荷按进度日归集）</template>
              </small>
            </div>
            <span :class="['tag', s.status === 'completed' ? 'blue' : s.status === 'cancelled' ? 'danger' : 'warning']">{{ statusLabel(s.status) }}</span>
            <CountdownPill
              v-if="s.status === 'pending'"
              :time="primaryTime(s)"
              :time-type="s.timeType"
              :created-at="s.createdAt"
              :start-time="s.startTime"
              :end-time="s.endTime"
              :deadline-time="s.deadlineTime"
              :remind-at="s.remindAt"
            />
            <span v-else class="muted">{{ s.status === 'completed' && s.completedAt ? `完成于 ${formatTime(s.completedAt)}` : '已取消' }}</span>
            <div class="top-actions" @click.stop>
              <button v-if="isGroupedView" class="icon-button row-menu-button mobile-only" title="更多操作" aria-label="更多操作" @click="openScheduleButtonMenu($event, group, s)"><MoreHorizontal :size="18" /></button>
              <select v-if="canReorder && s.status === 'pending'" :value="s.groupId || ''" aria-label="移动日程至分组" @click.stop @change="moveSchedule(s, $event)">
                <option value="" disabled>移动至</option><option v-for="target in store.taskGroups" :key="target.id" :value="target.id">{{ target.name }}</option>
              </select>
              <button v-if="canReorder && group.id !== 0" :disabled="index === 0" @click="moveScheduleOrder(group.id, group.items, s.id, -1)">上移</button>
              <button v-if="canReorder && group.id !== 0" :disabled="index === group.items.length - 1" @click="moveScheduleOrder(group.id, group.items, s.id, 1)">下移</button>
              <button v-if="s.status === 'pending'" @click="handleComplete(s)">{{ s.progressTrackingEnabled ? '提交进度' : '完成' }}</button>
              <button v-if="s.status === 'completed'" @click="handleAction(s, 'uncomplete')">恢复</button>
              <button v-if="s.status === 'pending'" @click="handleAction(s, 'cancel')">取消</button><button v-if="s.status === 'cancelled'" @click="handleAction(s, 'restore')">恢复</button><button @click="handleDelete(s.id)">删除</button>
            </div>
          </article>
        </div>
      </section>
      <p v-if="!store.schedules.length" class="hint" style="text-align:center;padding:40px 0">暂无日程</p>
      <PaginationBar
        :page="store.schedulePage.page"
        :size="store.schedulePage.size"
        :total="store.schedulePage.total"
        :loading="store.schedulePage.loading"
        @change="store.loadSchedules({ page: $event })"
        @resize="store.loadSchedules({ page: 1, size: $event })"
      />
    </section>

    <div v-if="store.scheduleModalOpen" class="modal-backdrop" @click.self="store.closeScheduleModal()">
      <section class="modal-panel"><div class="modal-head"><h2>新建日程</h2><button class="modal-close" @click="store.closeScheduleModal()">✕</button></div>
        <form @submit.prevent="createScheduleWithShortcut()">
          <label>标题<input v-model="store.scheduleForm.title" placeholder="可输入 /分组名 日程标题 快捷创建分组" /></label>
          <label>描述<textarea v-model="store.scheduleForm.description" rows="3" placeholder="补充地点或准备事项"></textarea></label>
          <div class="ai-row">
            <button type="button" class="ai-btn" @click="aiParseSchedule" :disabled="aiParsing">{{ aiParsing ? '解析中...' : 'AI 解析' }}</button>
            <span v-if="aiParseError" class="muted" style="color:#e53e3e;font-size:12px">{{ aiParseError }}</span>
          </div>
          <label>模块<select v-model="store.scheduleForm.groupId"><option v-for="group in store.taskGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select></label>
          <label>类型<select v-model="store.scheduleForm.timeType"><option value="point_event">安排事项</option><option value="deadline_task">待办任务</option><option value="duration_task">时间段任务</option></select></label>
          <div class="level-form-grid">
            <ScheduleLevelControl v-model="store.scheduleForm.urgencyLevel" label="紧急度" :labels="urgencyNames" />
            <ScheduleLevelControl v-model="store.scheduleForm.fatigueLevel" label="预计疲劳度" :labels="fatigueNames" :weights="store.fatigueProfile?.weights" />
          </div>
          <label v-if="store.scheduleForm.timeType === 'point_event'">发生时间<input v-model="store.scheduleForm.startTime" type="datetime-local" /></label>
          <label v-if="store.scheduleForm.timeType === 'deadline_task'">截止时间<input v-model="store.scheduleForm.deadlineTime" type="datetime-local" /></label>
          <template v-if="store.scheduleForm.timeType === 'duration_task'"><label>开始时间<input v-model="store.scheduleForm.startTime" type="datetime-local" /></label><label>结束时间<input v-model="store.scheduleForm.endTime" type="datetime-local" /></label></template>
          <FatiguePreviewInline v-if="createFatiguePreview" :preview="createFatiguePreview" />
          <ReminderShortcutPicker v-model="store.scheduleForm.remindAt" :base-time="reminderBaseTime" :base-label="reminderBaseLabel()" :presets="reminderPresets" :timezone="userTimezone" :disabled="store.loading" @error="store.notify" />
          <RepeatRuleEditor v-model:rrule="store.scheduleForm.rrule" v-model:excluded-dates="store.scheduleForm.excludedDates" />
          <fieldset class="progress-block" data-testid="create-daily-progress">
            <legend>每日进度</legend>
            <label class="progress-toggle">
              <input v-model="store.scheduleForm.progressTrackingEnabled" type="checkbox" :disabled="!canTrackProgress" />
              启用每日进度（长期任务按天提交完成比例与疲劳）
            </label>
            <small v-if="!canTrackProgress" class="muted">「安排事项」和重复任务不支持每日进度；类型改为「待办任务」或「时间段任务」后可开启。</small>
            <small v-else class="muted">开启后每天在任务详情提交累计完成比例，负荷按天计入个人完成负荷。</small>
          </fieldset>
          <div class="form-actions"><button type="button" @click="store.closeScheduleModal()">取消</button><button class="primary" :disabled="store.loading">保存</button></div>
        </form>
      </section>
    </div>
    <ContextMenu v-if="contextMenu" :x="contextMenu.x" :y="contextMenu.y" :items="contextItems" @select="selectContextAction" @close="contextMenu = null" />

    <div v-if="showDraftConfirm" class="modal-backdrop" @click.self="cancelAiDraft">
      <section class="modal-panel">
        <div class="modal-head"><h2>AI 草稿确认</h2><span class="muted" style="font-size: 12px;color:#6366f1">AI 不会直接写入数据库，请确认后保存</span><button class="modal-close" @click="cancelAiDraft">✕</button></div>
        <form @submit.prevent="confirmAiDraft">
          <label>标题<input v-model="aiDraft.title" required /></label>
          <label>描述<input v-model="aiDraft.description" placeholder="可选" /></label>
          <label>模块<select v-model="aiDraft.groupId"><option value="">未分组</option><option v-for="group in store.taskGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select></label>
          <label>类型<select v-model="aiDraft.timeType"><option value="point_event">安排事项</option><option value="deadline_task">待办任务</option><option value="duration_task">时间段任务</option></select></label>
          <div class="level-form-grid">
            <ScheduleLevelControl v-model="aiDraft.urgencyLevel" label="紧急度" :labels="urgencyNames" />
            <ScheduleLevelControl v-model="aiDraft.fatigueLevel" label="预计疲劳度" :labels="fatigueNames" :weights="store.fatigueProfile?.weights" />
          </div>
          <label v-if="aiDraft.timeType === 'point_event'">发生时间<input v-model="aiDraft.startTime" type="datetime-local" /></label>
          <label v-if="aiDraft.timeType === 'deadline_task'">截止时间<input v-model="aiDraft.deadlineTime" type="datetime-local" /></label>
          <template v-if="aiDraft.timeType === 'duration_task'"><label>开始时间<input v-model="aiDraft.startTime" type="datetime-local" /></label><label>结束时间<input v-model="aiDraft.endTime" type="datetime-local" /></label></template>
          <FatiguePreviewInline v-if="draftFatiguePreview" :preview="draftFatiguePreview" />
          <ReminderShortcutPicker v-model="aiDraft.remindAt" :base-time="aiReminderBaseTime" :base-label="aiReminderBaseLabel" :presets="reminderPresets" :timezone="userTimezone" @error="store.notify" />
          <div class="form-actions">
            <button type="button" @click="cancelAiDraft">取消</button>
            <button class="primary">确认保存</button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>
