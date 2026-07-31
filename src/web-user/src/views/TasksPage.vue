<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import ContextMenu from '../components/ContextMenu.vue'
import { countdown, statusLabel, isOverdue, primaryTime } from '../utils/helpers'
import type { MyTask, TeamMember } from '../types'

const router = useRouter()
const store = useAppStore()
const teamMembers = ref<TeamMember[]>([])
const collapsedGroups = ref<string[]>([])
const contextMenu = ref<{ x: number; y: number; task: MyTask; group: any } | null>(null)
const draggingTask = ref<{ id: number; groupId: number | null; teamId: number } | null>(null)
const filterKeyword = ref('')
const filterStatus = ref('')
const filterDateFrom = ref('')
const filterDateTo = ref('')
const selectedTeam = computed(() => store.teams.find(team => String(team.id) === store.taskForm.teamId))
const selectedTeamGroups = computed(() => store.teamTaskGroups[Number(store.taskForm.teamId)] || [])
const filteredMyTasks = computed(() => {
  return store.myTasks.filter(task => {
    if (filterKeyword.value && !task.title.toLowerCase().includes(filterKeyword.value.toLowerCase()) && !(task.teamName || '').toLowerCase().includes(filterKeyword.value.toLowerCase())) return false
    if (filterStatus.value) {
      const effectiveStatus = task.assignStatus || task.status
      if (effectiveStatus !== filterStatus.value) return false
    }
    if (filterDateFrom.value) {
      const time = primaryTime(task)
      if (time && new Date(time) < new Date(filterDateFrom.value + 'T00:00:00')) return false
    }
    if (filterDateTo.value) {
      const time = primaryTime(task)
      if (time && new Date(time) > new Date(filterDateTo.value + 'T23:59:59')) return false
    }
    return true
  })
})

const taskGroups = computed(() => {
  const byName = new Map<string, { teamId: number; teamName: string; groupId: number | null; groupName: string; items: MyTask[] }>()
  for (const task of filteredMyTasks.value) {
    const key = `${task.teamId}:${task.groupId || 0}`
    if (!byName.has(key)) byName.set(key, { teamId: task.teamId, teamName: task.teamName || '团队任务', groupId: task.groupId, groupName: task.groupName || '未分组', items: [] })
    byName.get(key)!.items.push(task)
  }
  return [...byName.values()]
    .map(group => ({ ...group, items: group.items.sort((a, b) => a.sortOrder - b.sortOrder) }))
    .sort((a, b) => a.teamName.localeCompare(b.teamName, 'zh-CN') || a.groupName.localeCompare(b.groupName, 'zh-CN'))
})

function toggleGroup(name: string) {
  collapsedGroups.value = collapsedGroups.value.includes(name)
    ? collapsedGroups.value.filter(value => value !== name)
    : [...collapsedGroups.value, name]
}
function goDetail(id: number) { router.push(`/tasks/${id}`) }
const selectableMembers = computed(() => teamMembers.value.filter(member => member.status === 'active'))
const canManageTask = (task: MyTask) => task.creatorId === store.profile?.id || ['owner', 'admin'].includes(store.teams.find(team => team.id === task.teamId)?.myRole || '')

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

watch(() => [...new Set(store.myTasks.map(task => task.teamId))].join(','), teamIds => {
  teamIds.split(',').filter(Boolean).forEach(teamId => store.loadTeamTaskGroups(Number(teamId)))
}, { immediate: true })

const aiBreaking = ref(false)
const aiBreakdownResult = ref('')
const showAiBreakdownModal = ref(false)
const aiBreakdownTasks = ref<{ title: string; selected: boolean; description: string; deadlineTime: string }[]>([])
async function aiBreakdownTask() {
  const text = store.taskForm.title.trim()
  if (!text) { store.notify('请先输入任务目标再使用 AI 拆解'); return }
  aiBreaking.value = true; aiBreakdownResult.value = ''
  try {
    const result = await store.aiRequest('/team-tasks/breakdown', { text })
    const tasks = result.tasks || []
    if (tasks.length > 0) {
      aiBreakdownTasks.value = tasks.map((t: any) => ({ title: t.title || '', selected: true, description: t.description || '', deadlineTime: t.deadlineTime ? toDatetimeLocal(t.deadlineTime) : '' }))
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
  showAiBreakdownModal.value = false
  for (const task of selected) {
    store.taskForm.title = task.title
    store.taskForm.deadlineTime = task.deadlineTime
    await store.createTask()
  }
  store.notify(`已创建 ${selected.length} 条子任务`)
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
  store.sortTeamTasks(group.teamId, group.groupId, ids)
}
function openTaskMenu(event: MouseEvent, group: any, task: MyTask) {
  contextMenu.value = { x: event.clientX, y: event.clientY, group, task }
}
function selectTaskAction(action: string) {
  const menu = contextMenu.value
  contextMenu.value = null
  if (!menu) return
  if (action === 'detail') goDetail(menu.task.id)
  if (action === 'accept') handleAction(menu.task, 'accept')
  if (action === 'reject') handleAction(menu.task, 'reject')
  if (action === 'complete') handleAction(menu.task, 'complete')
  if (action === 'edit-deadline') editTaskDeadline(menu.task)
  if (action === 'remove-deadline') removeTaskDeadline(menu.task)
}
function dropTask(group: any, targetId?: number) {
  const drag = draggingTask.value
  draggingTask.value = null
  if (!drag || !group.groupId || drag.teamId !== group.teamId) return
  if (drag.groupId !== group.groupId) {
    const task = store.myTasks.find(item => item.id === drag.id)
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
  const task = contextMenu.value?.task
  if (!task) return []
  return [
    { label: '查看详情', action: 'detail' },
    { label: '接受', action: 'accept', disabled: task.assignStatus !== 'pending' },
    { label: '拒绝', action: 'reject', disabled: task.assignStatus !== 'pending' },
    { label: '完成', action: 'complete', disabled: task.assignStatus !== 'accepted' },
    { separator: true as any, label: '' },
    { label: '修改截止时间', action: 'edit-deadline' },
    { label: '移除截止时间', action: 'remove-deadline' }
  ]
})
</script>

<template>
  <section class="split-layout">
    <section class="list-card">
      <div class="search-bar">
        <input v-model="filterKeyword" placeholder="搜索标题或团队..." class="search-input" />
        <select v-model="filterStatus"><option value="">全部状态</option><option value="pending">待接受</option><option value="accepted">已接受</option><option value="rejected">已拒绝</option><option value="completed">已完成</option></select>
        <input v-model="filterDateFrom" type="date" title="开始日期" />
        <input v-model="filterDateTo" type="date" title="截止日期" />
        <button v-if="filterKeyword || filterStatus || filterDateFrom || filterDateTo" class="plain-button" @click="filterKeyword='';filterStatus='';filterDateFrom='';filterDateTo=''" style="color:#e11d48">清除</button>
      </div>
      <div class="section-head"><h2>团队任务</h2></div>
      <form class="inline-form" @submit.prevent="store.createTask()">
        <select v-model="store.taskForm.teamId"><option value="">选择团队</option><option v-for="team in store.teams" :key="team.id" :value="team.id">{{ team.name }}</option></select>
        <select v-model="store.taskForm.groupId" :disabled="!store.taskForm.teamId"><option value="">选择分组</option><option v-for="group in selectedTeamGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select>
        <input v-model="store.taskForm.title" placeholder="任务标题" />
        <div class="ai-row">
          <button type="button" class="ai-btn" @click="aiBreakdownTask" :disabled="aiBreaking">{{ aiBreaking ? '拆解中...' : 'AI 拆解' }}</button>
          <span v-if="aiBreakdownResult" class="ai-hint">{{ aiBreakdownResult }}</span>
        </div>
        <input v-model="store.taskForm.deadlineTime" type="datetime-local" />
        <div class="assignee-picker"><label v-for="member in selectableMembers" :key="member.userId" class="check-option"><input v-model="store.taskForm.assigneeUserIds" type="checkbox" :value="member.userId" /><span>{{ member.nickname }}</span></label><span v-if="store.taskForm.teamId && selectableMembers.length === 0" class="muted">暂无可选成员</span></div>
        <button class="primary">创建</button>
      </form>

      <section v-for="group in taskGroups" :key="`${group.teamId}:${group.groupId}`" class="group-block" @dragover.prevent @drop="dropTask(group)">
        <div class="group-title"><button class="plain-button" @click="toggleGroup(`${group.teamId}:${group.groupId}`)"><span>{{ collapsedGroups.includes(`${group.teamId}:${group.groupId}`) ? '▸' : '▾' }} {{ group.teamName }} · {{ group.groupName }}</span></button><em>{{ group.items.filter(item => item.assignStatus !== 'completed' && item.status === 'active').length }}</em></div>
        <div v-if="!collapsedGroups.includes(`${group.teamId}:${group.groupId}`)">
          <article
            v-for="(task, index) in group.items"
            :key="task.assigneeId || task.id"
            :class="['task-card', { overdue: isOverdue(task), completed: task.assignStatus === 'completed' }]"
            :draggable="canManageTask(task)"
            style="cursor:pointer"
            @click="goDetail(task.id)"
            @dragstart="draggingTask = { id: task.id, groupId: task.groupId, teamId: task.teamId }"
            @dragover.prevent
            @drop.stop.prevent="dropTask(group, task.id)"
            @contextmenu.prevent="openTaskMenu($event, group, task)"
          >
            <div><strong>{{ task.title }}</strong><small :class="{ 'overdue-text': isOverdue(task) }">{{ task.teamName }} - {{ countdown(task.deadlineTime) }}</small></div>
            <span :class="['tag', task.assignStatus === 'accepted' ? 'blue' : task.assignStatus === 'rejected' ? 'danger' : 'warning']">{{ statusLabel(task.assignStatus || task.status) }}</span>
            <div class="top-actions" @click.stop>
              <select v-if="canManageTask(task)" :value="task.groupId || ''" aria-label="移动团队任务至分组" @click.stop @change="moveTask(task, $event)"><option value="" disabled>移动至</option><option v-for="target in taskGroupsFor(task.teamId)" :key="target.id" :value="target.id">{{ target.name }}</option></select>
              <button v-if="canManageTask(task) && group.groupId" :disabled="index === 0" @click="moveTaskOrder(group, task.id, -1)">上移</button>
              <button v-if="canManageTask(task) && group.groupId" :disabled="index === group.items.length - 1" @click="moveTaskOrder(group, task.id, 1)">下移</button>
              <button v-if="task.assignStatus === 'pending'" @click="handleAction(task, 'accept')">接受</button><button v-if="task.assignStatus === 'pending'" @click="handleAction(task, 'reject')">拒绝</button><button v-if="task.assignStatus === 'accepted'" @click="handleAction(task, 'complete')">完成</button>
            </div>
          </article>
        </div>
      </section>
      <p v-if="!store.myTasks.length" class="hint" style="text-align:center;padding:40px 0">暂无团队任务</p>
    </section>
    <aside class="detail-panel"><h2>任务说明</h2><p class="muted">{{ selectedTeam ? `当前创建到「${selectedTeam.name}」的已选分组。` : '创建团队任务后，执行人需要接受任务才能开始执行。' }}</p></aside>
    <ContextMenu v-if="contextMenu" :x="contextMenu.x" :y="contextMenu.y" :items="contextItems" @select="selectTaskAction" @close="contextMenu = null" />

    <div v-if="showAiBreakdownModal" class="modal-backdrop" @click.self="cancelAiBreakdown">
      <section class="modal-panel" style="max-width: 520px;">
        <div class="modal-head"><h2>AI 子任务拆解</h2><button class="modal-close" @click="cancelAiBreakdown">✕</button></div>
        <p class="muted" style="margin-bottom: 12px;">勾选需要创建的子任务，确认后将批量创建</p>
        <label v-for="(task, i) in aiBreakdownTasks" :key="i" class="check-option" style="display:flex;align-items:center;gap:8px;padding:8px 0;border-bottom:1px solid #edf1f6">
          <input v-model="task.selected" type="checkbox" />
          <div style="flex:1;min-width:0">
            <strong>{{ task.title }}</strong>
            <span v-if="task.deadlineTime" style="display:block;font-size:11px;color:#94a3b8">截止: {{ task.deadlineTime }}</span>
          </div>
        </label>
        <div class="form-actions" style="margin-top:14px">
          <button @click="cancelAiBreakdown">取消</button>
          <button class="primary" @click="confirmAiBreakdown">创建所选 ({{ aiBreakdownTasks.filter(t => t.selected).length }})</button>
        </div>
      </section>
    </div>
  </section>
</template>
