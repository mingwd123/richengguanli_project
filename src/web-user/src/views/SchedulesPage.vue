<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import ContextMenu from '../components/ContextMenu.vue'
import { formatTime, primaryTime, timeTypeLabel, statusLabel, countdown, isOverdue } from '../utils/helpers'
import type { Schedule } from '../types'

const router = useRouter()
const store = useAppStore()
const collapsedGroups = ref<string[]>([])
const aiParsing = ref(false)
const aiParseError = ref('')
const contextMenu = ref<{ x: number; y: number; type: 'group' | 'schedule'; group?: any; schedule?: Schedule } | null>(null)
const draggingGroupId = ref<number | null>(null)
const draggingSchedule = ref<{ id: number; fromGroupId: number | null } | null>(null)

function toDatetimeLocal(value: string) {
  const text = String(value).trim().replace(' ', 'T')
  if (!/[zZ]|[+-]\d{2}:?\d{2}$/.test(text)) return text.slice(0, 16)
  const date = new Date(text)
  if (Number.isNaN(date.getTime())) return text.slice(0, 16)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
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
    if (groupId) store.scheduleForm.groupId = groupId
    if (draft.title) store.scheduleForm.title = draft.title
    const nextTimeType = draft.startTime && draft.endTime
      ? 'duration_task'
      : draft.deadlineTime
        ? 'deadline_task'
        : ['point_event', 'deadline_task', 'duration_task'].includes(draft.timeType)
          ? draft.timeType
          : store.scheduleForm.timeType
    store.scheduleForm.timeType = nextTimeType
    if (draft.startTime) store.scheduleForm.startTime = toDatetimeLocal(draft.startTime)
    if (draft.endTime) store.scheduleForm.endTime = toDatetimeLocal(draft.endTime)
    if (draft.deadlineTime) store.scheduleForm.deadlineTime = toDatetimeLocal(draft.deadlineTime)
    if (draft.description) store.notify('已解析：' + draft.description.substring(0, 30))
    store.notify('AI 解析完成，请确认表单信息')
  } catch (e: any) {
    aiParseError.value = 'AI 解析失败: ' + (e.message || '服务不可用')
  } finally { aiParsing.value = false }
}

const scheduleGroups = computed(() => {
  const completed = store.schedules.filter(schedule => schedule.status === 'completed')
  const active = store.schedules.filter(schedule => schedule.status !== 'completed')
  const groups = store.taskGroups.map(group => ({
    id: group.id,
    name: group.name,
    sortOrder: group.sortOrder,
    items: active.filter(schedule => schedule.groupId === group.id).sort((a, b) => a.sortOrder - b.sortOrder)
  }))
  const groupedIds = new Set(store.taskGroups.map(group => group.id))
  const ungrouped = active.filter(schedule => !groupedIds.has(schedule.groupId || 0))
  if (ungrouped.length) groups.push({ id: 0, name: '未分组', sortOrder: Number.MAX_SAFE_INTEGER - 1, items: ungrouped })
  if (completed.length) groups.push({ id: -1, name: '已完成', sortOrder: Number.MAX_SAFE_INTEGER, items: completed.sort((a, b) => a.sortOrder - b.sortOrder) })
  return groups.sort((a, b) => a.sortOrder - b.sortOrder)
})

function toggleGroup(name: string) {
  collapsedGroups.value = collapsedGroups.value.includes(name)
    ? collapsedGroups.value.filter(value => value !== name)
    : [...collapsedGroups.value, name]
}
function goDetail(id: number) { router.push(`/schedules/${id}`) }
function handleAction(item: Schedule, action: string) { store.setScheduleStatus(item, action) }
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
  if (groupId <= 0 || target < 0 || target >= ids.length) return
  ;[ids[index], ids[target]] = [ids[target], ids[index]]
  store.sortSchedules(groupId, ids)
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
function openScheduleMenu(event: MouseEvent, group: any, schedule: Schedule) {
  contextMenu.value = { x: event.clientX, y: event.clientY, type: 'schedule', group, schedule }
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
  } else if (menu.schedule) {
    if (action === 'detail') goDetail(menu.schedule.id)
    if (action === 'complete') handleAction(menu.schedule, 'complete')
    if (action === 'uncomplete') handleAction(menu.schedule, 'uncomplete')
    if (action === 'cancel') handleAction(menu.schedule, 'cancel')
    if (action === 'delete') handleDelete(menu.schedule.id)
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
  if (!drag || group.id <= 0) return
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
const contextItems = computed(() => {
  const menu = contextMenu.value
  if (!menu) return []
  if (menu.type === 'group') return [
    { label: '重命名', action: 'rename' },
    { label: '上移', action: 'up' },
    { label: '下移', action: 'down' },
    { label: '删除', action: 'delete' }
  ]
  const schedule = menu.schedule!
  return [
    { label: '查看详情', action: 'detail' },
    { label: '完成', action: 'complete', disabled: schedule.status !== 'pending' },
    { label: '恢复', action: 'uncomplete', disabled: schedule.status !== 'completed' },
    { label: '取消', action: 'cancel', disabled: schedule.status !== 'pending' },
    { label: '删除', action: 'delete' }
  ]
})
</script>

<template>
  <section class="list-page">
    <div class="filter-bar"><button class="primary" @click="store.openScheduleModal()">新建日程</button></div>
    <section class="table-card">
      <section
        v-for="group in scheduleGroups"
        :key="group.id"
        class="group-block"
        @dragover.prevent
        @drop="dropSchedule(group)"
      >
        <div
          class="group-title"
          :draggable="group.id > 0"
          @dragstart="draggingGroupId = group.id"
          @drop.stop.prevent="dropGroup(group.id)"
          @contextmenu.prevent="openGroupMenu($event, group)"
        >
          <button class="plain-button" @click="toggleGroup(group.name)">
            <span>{{ collapsedGroups.includes(group.name) ? '▸' : '▾' }} {{ group.name }}</span>
          </button>
          <div class="group-actions" @click.stop>
            <em>{{ group.items.filter(item => item.status === 'pending').length }}</em>
            <template v-if="group.id > 0">
              <button title="上移" @click="moveGroup(group.id, -1)">上移</button>
              <button title="下移" @click="moveGroup(group.id, 1)">下移</button>
              <button @click="renameGroup(group.id, group.name)">重命名</button>
              <button @click="deleteGroup(group.id, group.name)">删除</button>
            </template>
          </div>
        </div>
        <div v-if="!collapsedGroups.includes(group.name)">
          <article
            v-for="(s, index) in group.items"
            :key="s.id"
            :class="['table-row', { overdue: isOverdue(s), completed: s.status === 'completed' }]"
            draggable="true"
            style="cursor:pointer"
            @click="goDetail(s.id)"
            @dragstart="draggingSchedule = { id: s.id, fromGroupId: s.groupId }"
            @dragover.prevent
            @drop.stop.prevent="dropSchedule(group, s.id)"
            @contextmenu.prevent="openScheduleMenu($event, group, s)"
          >
            <div><strong>{{ s.title }}</strong><small>{{ s.groupName }} - {{ timeTypeLabel(s.timeType) }}</small></div>
            <span :class="['tag', s.status === 'completed' ? 'blue' : s.status === 'cancelled' ? 'danger' : 'warning']">{{ statusLabel(s.status) }}</span>
            <span :class="{ 'overdue-text': isOverdue(s) }">{{ isOverdue(s) ? countdown(primaryTime(s)) : formatTime(primaryTime(s)) }}</span>
            <div class="top-actions" @click.stop>
              <select v-if="s.status !== 'completed'" :value="s.groupId || ''" aria-label="移动日程至分组" @click.stop @change="moveSchedule(s, $event)">
                <option value="" disabled>移动至</option><option v-for="target in store.taskGroups" :key="target.id" :value="target.id">{{ target.name }}</option>
              </select>
              <button v-if="group.id > 0" :disabled="index === 0" @click="moveScheduleOrder(group.id, group.items, s.id, -1)">上移</button>
              <button v-if="group.id > 0" :disabled="index === group.items.length - 1" @click="moveScheduleOrder(group.id, group.items, s.id, 1)">下移</button>
              <button v-if="s.status === 'pending'" @click="handleAction(s, 'complete')">完成</button>
              <button v-if="s.status === 'completed'" @click="handleAction(s, 'uncomplete')">恢复</button>
              <button v-if="s.status === 'pending'" @click="handleAction(s, 'cancel')">取消</button><button @click="handleDelete(s.id)">删除</button>
            </div>
          </article>
        </div>
      </section>
      <p v-if="!store.schedules.length" class="hint" style="text-align:center;padding:40px 0">暂无日程</p>
    </section>

    <div v-if="store.scheduleModalOpen" class="modal-backdrop" @click.self="store.closeScheduleModal()">
      <section class="modal-panel"><div class="modal-head"><h2>新建日程</h2><button class="modal-close" @click="store.closeScheduleModal()">✕</button></div>
        <form @submit.prevent="createScheduleWithShortcut()">
          <label>标题<input v-model="store.scheduleForm.title" placeholder="可输入 /分组名 日程标题 快捷创建分组" /></label>
          <div class="ai-row">
            <button type="button" class="ai-btn" @click="aiParseSchedule" :disabled="aiParsing">{{ aiParsing ? '解析中...' : 'AI 解析' }}</button>
            <span v-if="aiParseError" class="muted" style="color:#e53e3e;font-size:12px">{{ aiParseError }}</span>
          </div>
          <label>模块<select v-model="store.scheduleForm.groupId"><option v-for="group in store.taskGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select></label>
          <label>类型<select v-model="store.scheduleForm.timeType"><option value="point_event">安排事项</option><option value="deadline_task">待办任务</option><option value="duration_task">时间段任务</option></select></label>
          <label v-if="store.scheduleForm.timeType === 'point_event'">发生时间<input v-model="store.scheduleForm.startTime" type="datetime-local" /></label>
          <label v-if="store.scheduleForm.timeType === 'deadline_task'">截止时间<input v-model="store.scheduleForm.deadlineTime" type="datetime-local" /></label>
          <template v-if="store.scheduleForm.timeType === 'duration_task'"><label>开始时间<input v-model="store.scheduleForm.startTime" type="datetime-local" /></label><label>结束时间<input v-model="store.scheduleForm.endTime" type="datetime-local" /></label></template>
          <div class="form-actions"><button type="button" @click="store.closeScheduleModal()">取消</button><button class="primary" :disabled="store.loading">保存</button></div>
        </form>
      </section>
    </div>
    <ContextMenu v-if="contextMenu" :x="contextMenu.x" :y="contextMenu.y" :items="contextItems" @select="selectContextAction" @close="contextMenu = null" />
  </section>
</template>
