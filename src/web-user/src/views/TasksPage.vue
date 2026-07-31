<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import ContextMenu from '../components/ContextMenu.vue'
import PaginationBar from '../components/PaginationBar.vue'
import CountdownPill from '../components/CountdownPill.vue'
import TaskTimeline from '../components/TaskTimeline.vue'
import { MoreHorizontal, Plus, Sparkles } from 'lucide-vue-next'
import { statusLabel, isOverdue, normalizeTimelineItem } from '../utils/helpers'
import type { MyTask, TeamMember } from '../types'

const router = useRouter()
const store = useAppStore()
const teamMembers = ref<TeamMember[]>([])
const collapsedGroups = ref<string[]>([])
const contextMenu = ref<any>(null)
const draggingTask = ref<{ id: number; groupId: number | null; teamId: number } | null>(null)
const filterKeyword = ref('')
const filterStatus = ref('')
const filterDateFrom = ref('')
const filterDateTo = ref('')
const taskView = ref<'assigned' | 'created' | 'team'>('assigned')
const teamScopeId = ref('')
const selectedTeamGroups = computed(() => store.teamTaskGroups[Number(store.taskForm.teamId)] || [])
const visibleTasks = computed(() => taskView.value === 'assigned' ? store.myTasks : taskView.value === 'created' ? store.createdTasks : store.teamTasks)
const filteredMyTasks = computed(() => visibleTasks.value)
const activeTaskPage = computed(() => taskView.value === 'assigned' ? store.assignedTaskPage : taskView.value === 'created' ? store.createdTaskPage : store.teamTaskPage)

function loadCurrentTasks(patch: Record<string, any> = {}) {
  if (taskView.value === 'assigned') return store.loadAssignedTasks(patch)
  if (taskView.value === 'created') return store.loadCreatedTasks(patch)
  return store.loadTeamTasks(teamScopeId.value, patch)
}

let filterTimer: ReturnType<typeof setTimeout> | undefined
watch([filterKeyword, filterStatus, filterDateFrom, filterDateTo], () => {
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => loadCurrentTasks({
    page: 1,
    keyword: filterKeyword.value,
    status: filterStatus.value,
    dateFrom: filterDateFrom.value,
    dateTo: filterDateTo.value
  }), 250)
})
onUnmounted(() => clearTimeout(filterTimer))

const taskGroups = computed(() => {
  const byName = new Map<string, { teamId: number; teamName: string; groupId: number | null; groupName: string; items: MyTask[] }>()
  const completedByTeam = new Map<number, { teamId: number; teamName: string; groupId: number; groupName: string; items: MyTask[] }>()
  for (const task of filteredMyTasks.value) {
    if (isDoneTask(task)) {
      if (!completedByTeam.has(task.teamId)) completedByTeam.set(task.teamId, { teamId: task.teamId, teamName: task.teamName || '团队任务', groupId: -1, groupName: '已完成', items: [] })
      completedByTeam.get(task.teamId)!.items.push(task)
      continue
    }
    const key = `${task.teamId}:${task.groupId || 0}`
    if (!byName.has(key)) byName.set(key, { teamId: task.teamId, teamName: task.teamName || '团队任务', groupId: task.groupId, groupName: task.groupName || '未分组', items: [] })
    byName.get(key)!.items.push(task)
  }
  return [...byName.values(), ...completedByTeam.values()]
    .map(group => ({ ...group, items: group.items.sort((a, b) => Number(isOverdue(b)) - Number(isOverdue(a)) || a.sortOrder - b.sortOrder) }))
    .sort((a, b) => a.teamName.localeCompare(b.teamName, 'zh-CN') || Number(a.groupId === -1) - Number(b.groupId === -1) || a.groupName.localeCompare(b.groupName, 'zh-CN'))
})

const taskTimelineItems = computed(() => visibleTasks.value
  .filter(task => !isDoneTask(task) && task.status !== 'cancelled')
  .map(task => normalizeTimelineItem(task, 'team_task'))
  .filter(item => item.sortAt)
  .sort((a, b) => new Date(a.sortAt).getTime() - new Date(b.sortAt).getTime()))

function toggleGroup(name: string) {
  collapsedGroups.value = collapsedGroups.value.includes(name)
    ? collapsedGroups.value.filter(value => value !== name)
    : [...collapsedGroups.value, name]
}
function goDetail(id: number) { router.push(`/tasks/${id}`) }
const selectableMembers = computed(() => teamMembers.value.filter(member => member.status === 'active'))
const canManageTask = (task: MyTask) => task.creatorId === store.profile?.id || ['owner', 'admin'].includes(store.teams.find(team => team.id === task.teamId)?.myRole || '')
const canManageTeam = (teamId: number) => ['owner', 'admin'].includes(store.teams.find(team => team.id === teamId)?.myRole || '')
const canRestoreTask = (task: MyTask) => canManageTeam(task.teamId)
const isDoneTask = (task: MyTask) => taskView.value === 'assigned' ? task.assignStatus === 'completed' : task.status === 'completed'
const canSortTask = (task: MyTask, group: any) => group.groupId === -1 ? task.status === 'completed' && canManageTeam(task.teamId) : canManageTask(task)

watch(() => store.teams.map(team => team.id).join(','), () => {
  if (!teamScopeId.value && store.teams[0]) teamScopeId.value = String(store.teams[0].id)
}, { immediate: true })

watch([taskView, teamScopeId], async ([view, teamId]) => {
  filterStatus.value = ''
  if (view === 'assigned') await store.loadAssignedTasks()
  if (view === 'created') await store.loadCreatedTasks()
  if (view === 'team' && teamId) await store.loadTeamTasks(teamId)
}, { immediate: true })

watch(() => store.taskForm.teamId, async teamId => {
  store.taskForm.assigneeUserIds = []
  store.taskForm.groupId = ''
  teamMembers.value = []
  if (!teamId) return
  await store.loadTeamTaskGroups(teamId)
  try {
    const data = await store.request<{ list: TeamMember[] }>(`/teams/${teamId}/members`)
    teamMembers.value = data.list || []
  } catch (e: any) { store.notify(e.message || '加载成员失败') }
}, { immediate: true })

watch(() => [...new Set(visibleTasks.value.map(task => task.teamId))].join(','), teamIds => {
  teamIds.split(',').filter(Boolean).forEach(teamId => store.loadTeamTaskGroups(Number(teamId)))
}, { immediate: true })

const aiBreaking = ref(false)
const aiOptimizingDescription = ref(false)
const aiBreakdownResult = ref('')
const showAiBreakdownModal = ref(false)
const aiBreakdownTasks = ref<{ title: string; selected: boolean; description: string; deadlineTime: string; assigneeUserIds: number[] }[]>([])
async function aiOptimizeCreateDescription() {
  const text = store.taskForm.description.trim() || store.taskForm.title.trim()
  if (!text) { store.notify('请先填写任务标题或说明'); return }
  aiOptimizingDescription.value = true
  try {
    const result = await store.aiRequest('/text/optimize-task-description', { text })
    if (!result.description) { store.notify('AI 未生成优化建议'); return }
    store.taskForm.description = result.description
    store.notify('优化结果已填入任务说明')
  } catch (e: any) { store.notify(e.message || '优化失败') } finally { aiOptimizingDescription.value = false }
}
async function aiBreakdownTask() {
  const text = store.taskForm.title.trim()
  if (!text) { store.notify('请先输入任务目标再使用 AI 拆解'); return }
  aiBreaking.value = true; aiBreakdownResult.value = ''
  try {
    const result = await store.aiRequest('/team-tasks/breakdown', { text })
    const tasks = result.tasks || []
    if (tasks.length > 0) {
      aiBreakdownTasks.value = tasks.map((t: any) => ({ title: t.title || '', selected: true, description: t.description || '', deadlineTime: t.deadlineTime ? toDatetimeLocal(t.deadlineTime) : '', assigneeUserIds: [...store.taskForm.assigneeUserIds] }))
      showAiBreakdownModal.value = true
    } else {
      aiBreakdownResult.value = 'AI 未能拆解出子任务，请手动创建'
    }
  } catch (e: any) {
    aiBreakdownResult.value = '拆解失败: ' + (e.message || '服务不可用')
  } finally { aiBreaking.value = false }
}
async function confirmAiBreakdown() {
  const selected = aiBreakdownTasks.value.filter(t => t.selected)
  if (!selected.length) { store.notify('请至少选择一条子任务'); return }
  if (selected.some(task => !task.title.trim() || !task.assigneeUserIds.length)) { store.notify('所选子任务需要标题和执行人'); return }
  let created = 0
  for (const task of selected) {
    store.taskForm.title = task.title
    store.taskForm.description = task.description
    store.taskForm.deadlineTime = task.deadlineTime
    store.taskForm.assigneeUserIds = [...task.assigneeUserIds]
    if (await store.createTask()) created++
  }
  showAiBreakdownModal.value = false
  store.notify(`已创建 ${created} 条子任务`)
}
function cancelAiBreakdown() {
  showAiBreakdownModal.value = false
  aiBreakdownResult.value = ''
}
function toDatetimeLocal(value: string) {
  const text = String(value).trim().replace(' ', 'T')
  if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(text)) return text.slice(0, 16)
  const date = new Date(text)
  if (Number.isNaN(date.getTime())) return text.slice(0, 16)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function createTaskWithShortcut() {
  const match = store.taskForm.title.trim().match(/^\/([^\s/]+)\s+(.+)$/)
  if (match && store.taskForm.teamId) {
    let group = selectedTeamGroups.value.find(item => item.name === match[1])
    if (!group && canManageTeam(Number(store.taskForm.teamId))) group = await store.createTeamTaskGroup(Number(store.taskForm.teamId), match[1]) || undefined
    if (!group) { store.notify(`未找到团队分组：${match[1]}`); return }
    store.taskForm.groupId = String(group.id)
    store.taskForm.title = match[2]
  }
  await store.createTask()
}

async function createTeamGroup(teamId = Number(store.taskForm.teamId)) {
  if (!teamId || !canManageTeam(teamId)) { store.notify('只有团队管理员可以管理分组'); return }
  const name = window.prompt('输入团队分组名称')
  if (name?.trim()) await store.createTeamTaskGroup(teamId, name)
}
async function renameTeamGroup(group: any) {
  const source = taskGroupsFor(group.teamId).find(item => item.id === group.groupId)
  if (!source) return
  const name = window.prompt('输入新的分组名称', source.name)
  if (name?.trim()) await store.updateTeamTaskGroup(group.teamId, { ...source, name: name.trim() })
}
async function deleteTeamGroup(group: any) {
  if (window.confirm(`确定删除分组「${group.groupName}」吗？其中任务会迁移到其他分组。`)) await store.deleteTeamTaskGroup(group.teamId, group.groupId)
}
function moveTeamGroup(group: any, direction: -1 | 1) {
  const ids = taskGroupsFor(group.teamId).map(item => item.id)
  const index = ids.indexOf(group.groupId)
  const target = index + direction
  if (index < 0 || target < 0 || target >= ids.length) return
  ;[ids[index], ids[target]] = [ids[target], ids[index]]
  store.sortTeamTaskGroups(group.teamId, ids)
}

function handleAction(task: MyTask, action: string) { store.taskAction(task, action) }
function moveTask(task: MyTask, event: Event) {
  const groupId = (event.target as HTMLSelectElement).value
  if (groupId && Number(groupId) !== task.groupId) store.moveTeamTaskGroup(task, groupId)
}
function taskGroupsFor(teamId: number) { return store.teamTaskGroups[teamId] || [] }
function moveTaskOrder(group: { teamId: number; groupId: number | null; items: MyTask[] }, taskId: number, direction: -1 | 1) {
  if (!group.groupId) return
  const ids = group.items.map(task => task.id)
  const index = ids.indexOf(taskId)
  const target = index + direction
  if (target < 0 || target >= ids.length) return
  ;[ids[index], ids[target]] = [ids[target], ids[index]]
  if (group.groupId === -1) store.sortCompletedTeamTasks(group.teamId, ids)
  else store.sortTeamTasks(group.teamId, group.groupId, ids)
}
function openTaskMenu(event: MouseEvent, group: any, task: MyTask) {
  contextMenu.value = { x: event.clientX, y: event.clientY, type: 'task', group, task }
}
function openTaskButtonMenu(event: MouseEvent, group: any, task: MyTask) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  contextMenu.value = { x: Math.max(8, rect.right - 148), y: rect.bottom + 4, type: 'task', group, task }
}
function openTeamGroupMenu(event: MouseEvent, group: any) {
  if (group.groupId <= 0 || !canManageTeam(group.teamId)) return
  contextMenu.value = { x: event.clientX, y: event.clientY, type: 'group', group }
}
function openTeamGroupButtonMenu(event: MouseEvent, group: any) {
  if (group.groupId <= 0 || !canManageTeam(group.teamId)) return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  contextMenu.value = { x: Math.max(8, rect.right - 148), y: rect.bottom + 4, type: 'group', group }
}
function openManagedGroupMenu(event: MouseEvent, group: any) {
  const teamId = Number(store.taskForm.teamId)
  if (!teamId || !canManageTeam(teamId)) return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  contextMenu.value = { x: Math.max(8, rect.right - 148), y: rect.bottom + 4, type: 'group', group: { teamId, groupId: group.id, groupName: group.name, items: [] } }
}
function selectTaskAction(action: string) {
  const menu = contextMenu.value
  contextMenu.value = null
  if (!menu) return
  if (menu.type === 'group') {
    if (action === 'rename-group') renameTeamGroup(menu.group)
    if (action === 'delete-group') deleteTeamGroup(menu.group)
    if (action === 'group-up') moveTeamGroup(menu.group, -1)
    if (action === 'group-down') moveTeamGroup(menu.group, 1)
    if (action === 'toggle-group') toggleGroup(`${menu.group.teamId}:${menu.group.groupId}`)
    return
  }
  if (action === 'detail') goDetail(menu.task.id)
  if (action === 'edit') goDetail(menu.task.id)
  if (action === 'accept') handleAction(menu.task, 'accept')
  if (action === 'reject') handleAction(menu.task, 'reject')
  if (action === 'complete') handleAction(menu.task, 'complete')
  if (action === 'cancel') handleAction(menu.task, 'cancel')
  if (action === 'restore') handleAction(menu.task, 'restore')
  if (action === 'edit-deadline') editTaskDeadline(menu.task)
  if (action === 'remove-deadline') removeTaskDeadline(menu.task)
  if (action === 'move-group') moveTaskToGroup(menu.task)
  if (action === 'delete') deleteTask(menu.task)
}
function dropTask(group: any, targetId?: number) {
  const drag = draggingTask.value
  draggingTask.value = null
  if (!drag || !group.groupId || drag.teamId !== group.teamId) return
  if (group.groupId === -1) {
    const task = visibleTasks.value.find(item => item.id === drag.id)
    if (!task || task.status !== 'completed' || !canManageTask(task)) return
    const ids = group.items.map((item: MyTask) => item.id)
    const from = ids.indexOf(drag.id)
    const to = targetId ? ids.indexOf(targetId) : ids.length - 1
    if (from < 0 || to < 0 || from === to) return
    ids.splice(to, 0, ids.splice(from, 1)[0])
    store.sortCompletedTeamTasks(group.teamId, ids)
    return
  }
  if (drag.groupId !== group.groupId) {
    const task = visibleTasks.value.find(item => item.id === drag.id)
    if (task && canManageTask(task)) store.moveTeamTaskGroup(task, group.groupId)
    return
  }
  const ids = group.items.map((task: MyTask) => task.id)
  const from = ids.indexOf(drag.id)
  const to = targetId ? ids.indexOf(targetId) : ids.length - 1
  if (from < 0 || to < 0 || from === to) return
  ids.splice(to, 0, ids.splice(from, 1)[0])
  store.sortTeamTasks(group.teamId, group.groupId, ids)
}
async function moveTaskToGroup(task: MyTask) {
  const name = window.prompt('输入目标分组名称\n可选：' + taskGroupsFor(task.teamId).map(group => group.name).join('、'), task.groupName || '')
  const group = taskGroupsFor(task.teamId).find(item => item.name === name?.trim())
  if (!group) { if (name?.trim()) store.notify(`未找到团队分组：${name.trim()}`); return }
  await store.moveTeamTaskGroup(task, group.id)
}
async function deleteTask(task: MyTask) {
  if (!window.confirm(`确定删除任务「${task.title}」吗？`)) return
  try { await store.request(`/team-tasks/${task.id}`, { method: 'DELETE' }); await store.loadAll(); store.notify('团队任务已删除') } catch (e: any) { store.notify(e.message || '删除失败') }
}
async function editTaskDeadline(task: MyTask) {
  const current = task.deadlineTime || ''
  const userInput = window.prompt('请输入新截止时间 (格式: yyyy-MM-dd HH:mm)', current.slice(0, 16).replace('T', ' '))
  if (userInput === null || !userInput.trim()) return
  try {
    await store.request(`/team-tasks/${task.id}/time`, { method: 'PUT', body: JSON.stringify({ deadlineTime: new Date(userInput.trim()).toISOString() }) })
    await store.loadAll()
    store.notify('截止时间已更新')
  } catch (e: any) { store.notify(e.message || '更新失败') }
}
async function removeTaskDeadline(task: MyTask) {
  if (!window.confirm('确定移除截止时间吗？')) return
  try {
    await store.request(`/team-tasks/${task.id}/time`, { method: 'PUT', body: JSON.stringify({ deadlineTime: '' }) })
    await store.loadAll()
    store.notify('截止时间已移除')
  } catch (e: any) { store.notify(e.message || '移除失败') }
}
const contextItems = computed(() => {
  const menu = contextMenu.value
  if (menu?.type === 'group') return [
    { label: '折叠/展开', action: 'toggle-group' },
    { label: '重命名分组', action: 'rename-group' },
    { label: '上移', action: 'group-up' },
    { label: '下移', action: 'group-down' },
    { separator: true as any, label: '' },
    { label: '删除分组', action: 'delete-group' }
  ]
  const task = menu?.task
  if (!task) return []
  return [
    { label: '查看详情', action: 'detail' },
    { label: '编辑任务', action: 'edit', disabled: !canManageTask(task) },
    { label: '接受', action: 'accept', disabled: task.assignStatus !== 'pending' },
    { label: '拒绝', action: 'reject', disabled: task.assignStatus !== 'pending' },
    { label: '完成', action: 'complete', disabled: task.assignStatus !== 'accepted' },
    { label: '取消任务', action: 'cancel', disabled: !canManageTask(task) || task.status !== 'active' },
    { label: '恢复任务', action: 'restore', disabled: !canRestoreTask(task) || task.status !== 'cancelled' },
    { separator: true as any, label: '' },
    { label: '修改截止时间', action: 'edit-deadline', disabled: !canManageTask(task) },
    { label: '移除截止时间', action: 'remove-deadline', disabled: !canManageTask(task) },
    { label: '移动到分组', action: 'move-group', disabled: !canManageTask(task) || task.status === 'completed' },
    { separator: true as any, label: '' },
    { label: '删除任务', action: 'delete', disabled: !canManageTask(task) }
  ]
})
</script>

<template>
  <section class="split-layout">
    <section class="list-card">
      <div class="task-view-bar" role="tablist" aria-label="任务视图">
        <button :class="{ active: taskView === 'assigned' }" role="tab" :aria-selected="taskView === 'assigned'" @click="taskView = 'assigned'">分配给我</button>
        <button :class="{ active: taskView === 'created' }" role="tab" :aria-selected="taskView === 'created'" @click="taskView = 'created'">我创建的</button>
        <button :class="{ active: taskView === 'team' }" role="tab" :aria-selected="taskView === 'team'" @click="taskView = 'team'">团队全部</button>
        <select v-if="taskView === 'team'" v-model="teamScopeId" aria-label="选择团队">
          <option v-for="team in store.teams" :key="team.id" :value="team.id">{{ team.name }}</option>
        </select>
      </div>
      <div class="search-bar">
        <input v-model="filterKeyword" placeholder="搜索标题或团队..." class="search-input" />
        <select v-model="filterStatus">
          <option value="">全部状态</option>
          <template v-if="taskView === 'assigned'"><option value="pending">待接受</option><option value="accepted">已接受</option><option value="rejected">已拒绝</option><option value="completed">已完成</option></template>
          <template v-else><option value="active">进行中</option><option value="cancelled">已取消</option></template>
        </select>
        <input v-model="filterDateFrom" type="date" title="开始日期" />
        <input v-model="filterDateTo" type="date" title="截止日期" />
        <select v-model="activeTaskPage.sort" aria-label="任务排序" @change="loadCurrentTasks({ page: 1 })">
          <option value="manual">分组顺序</option><option value="time_asc">时间升序</option><option value="time_desc">时间降序</option><option value="created_desc">最近创建</option><option value="title_asc">标题排序</option>
        </select>
        <button v-if="filterKeyword || filterStatus || filterDateFrom || filterDateTo" class="plain-button" @click="filterKeyword='';filterStatus='';filterDateFrom='';filterDateTo=''" style="color:#e11d48">清除</button>
      </div>
      <div class="section-head"><h2>团队任务</h2></div>
      <form class="inline-form team-task-form" @submit.prevent="createTaskWithShortcut">
        <select v-model="store.taskForm.teamId"><option value="">选择团队</option><option v-for="team in store.teams" :key="team.id" :value="team.id">{{ team.name }}</option></select>
        <div class="group-picker-row">
          <select v-model="store.taskForm.groupId" :disabled="!store.taskForm.teamId"><option value="">选择分组</option><option v-for="group in selectedTeamGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select>
          <button v-if="store.taskForm.teamId && canManageTeam(Number(store.taskForm.teamId))" type="button" class="icon-button" title="新建团队分组" aria-label="新建团队分组" @click="createTeamGroup()" @contextmenu.prevent="createTeamGroup()"><Plus :size="17" /></button>
        </div>
        <input v-model="store.taskForm.title" class="task-title-field" placeholder="任务标题" />
        <textarea v-model="store.taskForm.description" class="task-description-field" rows="2" placeholder="任务说明（可选）"></textarea>
        <div class="ai-row">
          <button type="button" class="ai-btn" @click="aiBreakdownTask" :disabled="aiBreaking">{{ aiBreaking ? '拆解中...' : 'AI 拆解' }}</button>
          <button type="button" class="ai-btn" :disabled="aiOptimizingDescription" @click="aiOptimizeCreateDescription"><Sparkles :size="15" />{{ aiOptimizingDescription ? '优化中...' : '优化说明' }}</button>
          <span v-if="aiBreakdownResult" class="ai-hint">{{ aiBreakdownResult }}</span>
        </div>
        <div class="task-time-grid">
          <label><span>开始</span><input v-model="store.taskForm.startTime" type="datetime-local" aria-label="开始时间" /></label>
          <label><span>截止</span><input v-model="store.taskForm.deadlineTime" type="datetime-local" aria-label="截止时间" /></label>
          <label><span>提醒</span><input v-model="store.taskForm.remindAt" type="datetime-local" aria-label="提醒时间" /></label>
        </div>
        <div class="assignee-picker">
          <strong>执行人</strong>
          <label v-for="member in selectableMembers" :key="member.userId" class="check-option"><input v-model="store.taskForm.assigneeUserIds" type="checkbox" :value="member.userId" /><span>{{ member.nickname }}</span></label>
          <span v-if="store.taskForm.teamId && selectableMembers.length === 0" class="muted">暂无可选成员</span>
        </div>
        <button class="primary task-create-submit">创建</button>
      </form>

      <div v-if="store.taskForm.teamId && canManageTeam(Number(store.taskForm.teamId))" class="team-group-strip">
        <span v-for="group in selectedTeamGroups" :key="group.id">{{ group.name }}<button class="icon-button" :title="`${group.name} 分组操作`" :aria-label="`${group.name} 分组操作`" @click="openManagedGroupMenu($event, group)" @contextmenu.prevent="openManagedGroupMenu($event, group)"><MoreHorizontal :size="15" /></button></span>
      </div>

      <section v-for="group in taskGroups" :key="`${group.teamId}:${group.groupId}`" class="group-block" @dragover.prevent @drop="dropTask(group)">
        <div class="group-title" @contextmenu.prevent="openTeamGroupMenu($event, group)">
          <button class="plain-button" @click="toggleGroup(`${group.teamId}:${group.groupId}`)"><span>{{ collapsedGroups.includes(`${group.teamId}:${group.groupId}`) ? '▸' : '▾' }} {{ group.teamName }} · {{ group.groupName }}</span></button>
          <div class="group-actions"><em>{{ group.items.filter(item => !isDoneTask(item) && item.status === 'active').length }}</em><button v-if="group.groupId > 0 && canManageTeam(group.teamId)" class="icon-button row-menu-button mobile-only" title="更多分组操作" aria-label="更多分组操作" @click="openTeamGroupButtonMenu($event, group)"><MoreHorizontal :size="18" /></button></div>
        </div>
        <div v-if="!collapsedGroups.includes(`${group.teamId}:${group.groupId}`)">
          <article
            v-for="(task, index) in group.items"
            :key="task.assigneeId || task.id"
            :class="['task-card', { overdue: isOverdue(task), completed: isDoneTask(task) }]"
            :draggable="canSortTask(task, group)"
            style="cursor:pointer"
            @click="goDetail(task.id)"
            @dragstart="draggingTask = { id: task.id, groupId: group.groupId, teamId: task.teamId }"
            @dragover.prevent
            @drop.stop.prevent="dropTask(group, task.id)"
            @contextmenu.prevent="openTaskMenu($event, group, task)"
          >
            <div><strong>{{ task.title }}</strong><small>{{ task.groupName || '团队任务' }}</small></div>
            <div class="assignee-stack" :title="(task.assignees || []).map(item => item.nickname).join('、')">
              <template v-for="assignee in (task.assignees || []).slice(0, 4)" :key="assignee.assigneeId">
                <img v-if="assignee.avatarUrl" :src="assignee.avatarUrl" alt="" />
                <span v-else>{{ assignee.nickname?.slice(0, 1) || 'U' }}</span>
              </template>
              <small v-if="(task.assignees || []).length > 4">+{{ (task.assignees || []).length - 4 }}</small>
            </div>
            <CountdownPill v-if="task.status === 'active' && !['completed', 'rejected'].includes(task.assignStatus || '')" :time="task.deadlineTime || task.startTime" :created-at="task.createdAt" :start-time="task.startTime" :deadline-time="task.deadlineTime" :remind-at="task.remindAt" />
            <span :class="['tag', task.assignStatus === 'accepted' ? 'blue' : task.assignStatus === 'rejected' ? 'danger' : 'warning']">{{ statusLabel(task.assignStatus || task.status) }}</span>
            <div class="top-actions" @click.stop>
              <button class="icon-button row-menu-button mobile-only" title="更多操作" aria-label="更多操作" @click="openTaskButtonMenu($event, group, task)"><MoreHorizontal :size="18" /></button>
              <select v-if="canManageTask(task) && !isDoneTask(task)" :value="task.groupId || ''" aria-label="移动团队任务至分组" @click.stop @change="moveTask(task, $event)"><option value="" disabled>移动至</option><option v-for="target in taskGroupsFor(task.teamId)" :key="target.id" :value="target.id">{{ target.name }}</option></select>
              <button v-if="canSortTask(task, group) && group.groupId" :disabled="index === 0" @click="moveTaskOrder(group, task.id, -1)">上移</button>
              <button v-if="canSortTask(task, group) && group.groupId" :disabled="index === group.items.length - 1" @click="moveTaskOrder(group, task.id, 1)">下移</button>
              <button v-if="task.assignStatus === 'pending'" @click="handleAction(task, 'accept')">接受</button><button v-if="task.assignStatus === 'pending'" @click="handleAction(task, 'reject')">拒绝</button><button v-if="task.assignStatus === 'accepted'" @click="handleAction(task, 'complete')">完成</button>
            </div>
          </article>
        </div>
      </section>
      <p v-if="!filteredMyTasks.length" class="hint" style="text-align:center;padding:40px 0">当前视图暂无团队任务</p>
      <PaginationBar
        :page="activeTaskPage.page"
        :size="activeTaskPage.size"
        :total="activeTaskPage.total"
        :loading="activeTaskPage.loading"
        @change="loadCurrentTasks({ page: $event })"
        @resize="loadCurrentTasks({ page: 1, size: $event })"
      />
    </section>
    <aside class="detail-panel"><h2>截止时间轴</h2><TaskTimeline :items="taskTimelineItems" /></aside>
    <ContextMenu v-if="contextMenu" :x="contextMenu.x" :y="contextMenu.y" :items="contextItems" @select="selectTaskAction" @close="contextMenu = null" />

    <div v-if="showAiBreakdownModal" class="modal-backdrop" @click.self="cancelAiBreakdown">
      <section class="modal-panel" style="max-width: 680px;">
        <div class="modal-head"><h2>AI 子任务拆解</h2><button class="modal-close" @click="cancelAiBreakdown">✕</button></div>
        <p class="muted" style="margin-bottom: 12px;">勾选需要创建的子任务，确认后将批量创建</p>
        <article v-for="(task, i) in aiBreakdownTasks" :key="i" class="ai-breakdown-item">
          <label class="check-option"><input v-model="task.selected" type="checkbox" /><span>创建此项</span></label>
          <input v-model="task.title" :disabled="!task.selected" placeholder="子任务标题" />
          <textarea v-model="task.description" :disabled="!task.selected" rows="2" placeholder="子任务说明"></textarea>
          <input v-model="task.deadlineTime" :disabled="!task.selected" type="datetime-local" aria-label="子任务截止时间" />
          <div class="assignee-picker"><label v-for="member in selectableMembers" :key="member.userId" class="check-option"><input v-model="task.assigneeUserIds" :disabled="!task.selected" type="checkbox" :value="member.userId" /><span>{{ member.nickname }}</span></label></div>
        </article>
        <div class="form-actions" style="margin-top:14px">
          <button @click="cancelAiBreakdown">取消</button>
          <button class="primary" @click="confirmAiBreakdown">创建所选 ({{ aiBreakdownTasks.filter(t => t.selected).length }})</button>
        </div>
      </section>
    </div>
  </section>
</template>
