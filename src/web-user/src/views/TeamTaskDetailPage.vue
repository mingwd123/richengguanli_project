<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { Sparkles } from 'lucide-vue-next'
import ScheduleLevelControl from '../components/ScheduleLevelControl.vue'
import { canCorrectTeamTaskAssignee, canDeleteTeamTask, formatTime, countdown, getDisplayTimezone, statusLabel, toDatetimeLocalInTimezone, urgency, zonedDateTimeToIso } from '../utils/helpers'
import type { TeamTask, TeamTaskAssignee, TeamTaskReassignmentCandidate, TaskGroup } from '../types'

const eventTypeLabels: Record<string, string> = {
  created: '创建', assigned: '分配', accepted: '接受', rejected: '拒绝',
  completed: '完成', cancelled: '取消', restored: '恢复', reassigned: '重新分配',
  assignment_proposed: '提议分配', approval_requested: '提交审批', approved: '审批通过', approval_rejected: '审批拒绝', approval_resubmitted: '重新提交审批',
  assignee_removed: '成员移除',
  time_updated: '修改时间', status_corrected: '修正状态'
}

const fatigueNames = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']

const props = defineProps<{ id: string }>()
const router = useRouter()
const store = useAppStore()

const task = ref<TeamTask | null>(null)
const loading = ref(false)
const aiOptimizing = ref(false)
const optimizedDesc = ref('')
const editOpen = ref(false)
const reviewSaving = ref(false)
const taskGroups = ref<TaskGroup[]>([])
const editForm = reactive({ title: '', description: '', groupId: '', startTime: '', deadlineTime: '', remindAt: '' })
const initialRemindAt = ref('')
const userTimezone = computed(() => store.profile?.timezone || getDisplayTimezone())
const completingFatigueLevel = ref<number | null>(null)
const completingSubmitting = ref(false)
const completeOpen = ref(false)
const fatigueTrackingOn = computed(() => Boolean(store.fatigueProfile?.fatigueTrackingEnabled && store.fatigueProfile?.featureEnabled))
const isActionable = computed(() => Boolean(task.value && ['active', 'unassigned'].includes(task.value.status)))
const myAssignStatus = computed(() => task.value?.assignStatus || '')

async function handleMyAction(action: 'accept' | 'reject') {
  if (!task.value) return
  const teamId = task.value.teamId
  try {
    await store.request(`/team-tasks/${task.value.id}/${action}`, { method: 'POST' })
    await Promise.all([loadDetail(), store.loadAll(), store.loadTeamTasks(teamId)])
    store.notify(action === 'accept' ? '已接受任务' : '已拒绝任务')
  } catch (e: any) { store.notify(e.message || '操作失败') }
}

function handleComplete() {
  if (!task.value) return
  if (fatigueTrackingOn.value) {
    completingFatigueLevel.value = null
    completeOpen.value = true
    return
  }
  store.completeTeamTask(task.value).then(ok => { if (ok) loadDetail() })
}

async function submitComplete() {
  if (!task.value || completingFatigueLevel.value === null) {
    store.notify('请选择完成疲劳程度')
    return
  }
  completingSubmitting.value = true
  const ok = await store.completeTeamTask(task.value, completingFatigueLevel.value)
  completingSubmitting.value = false
  if (ok) { completeOpen.value = false; await loadDetail() }
}
async function aiOptimizeDesc() {
  if (!task.value?.description) return
  aiOptimizing.value = true; optimizedDesc.value = ''
  try {
    const result = await store.aiRequest('/text/optimize-task-description', { text: task.value.description })
    optimizedDesc.value = result.description || ''
    if (!optimizedDesc.value) optimizedDesc.value = 'AI 未能生成优化建议'
  } catch (e: any) {
    optimizedDesc.value = '优化失败: ' + (e.message || '服务不可用')
  } finally { aiOptimizing.value = false }
}

async function applyOptimizedDescription() {
  if (!task.value || !optimizedDesc.value || optimizedDesc.value.startsWith('优化失败')) return
  try {
    await store.request(`/team-tasks/${task.value.id}`, { method: 'PUT', body: JSON.stringify({ description: optimizedDesc.value }) })
    optimizedDesc.value = ''
    await Promise.all([loadDetail(), store.loadAll(), store.loadTeamTasks(task.value.teamId)])
    store.notify('优化后的描述已保存')
  } catch (e: any) { store.notify(e.message || '保存失败') }
}

const isCreatorOrAdmin = computed(() => {
  if (!task.value) return false
  if (task.value.canManage !== undefined) return task.value.canManage
  if (!store.profile) return false
  const team = store.teams.find(t => t.id === task.value?.teamId)
  return task.value.creatorId === store.profile.id || team?.myRole === 'owner' || team?.myRole === 'admin'
})
const isTeamAdmin = computed(() => {
  if (!task.value) return false
  const role = store.teams.find(team => team.id === task.value?.teamId)?.myRole
  return role === 'owner' || role === 'admin'
})
const hasVacancies = computed(() => Number(task.value?.unassignedCount || 0) > 0)
const hasReassignmentCandidates = computed(() => Boolean(task.value?.reassignmentCandidates?.length))
const canCancelTask = computed(() => Boolean(task.value && isCreatorOrAdmin.value && ['pending_approval', 'active', 'unassigned', 'all_rejected'].includes(task.value.status)))
const taskStatusClass = computed(() => {
  if (!task.value) return 'warning'
  if (task.value.status === 'completed') return 'blue'
  if (['cancelled', 'all_rejected', 'approval_rejected'].includes(task.value.status)) return 'danger'
  return 'warning'
})

async function loadDetail() {
  loading.value = true
  try {
    const data = await store.request<TeamTask>(`/team-tasks/${props.id}`)
    task.value = data
  } catch (e: any) {
    store.notify(e.message || '加载任务详情失败')
  } finally {
    loading.value = false
  }
}

function toDatetimeLocal(value: string) {
  return toDatetimeLocalInTimezone(value, userTimezone.value)
}

async function startEdit() {
  if (!task.value) return
  taskGroups.value = await store.loadTeamTaskGroups(task.value.teamId)
  Object.assign(editForm, {
    title: task.value.title,
    description: task.value.description || '',
    groupId: String(task.value.groupId || ''),
    startTime: toDatetimeLocal(task.value.startTime),
    deadlineTime: toDatetimeLocal(task.value.deadlineTime),
    remindAt: toDatetimeLocal(task.value.pendingReminders?.[0]?.remindAt || ''),
  })
  initialRemindAt.value = editForm.remindAt
  editOpen.value = true
}

async function saveEdit() {
  if (!task.value || !editForm.title.trim() || !editForm.groupId) {
    store.notify('请填写标题并选择分组')
    return
  }
  try {
    const timezone = userTimezone.value
    const payload: Record<string, any> = {
      title: editForm.title.trim(),
      description: editForm.description,
      groupId: Number(editForm.groupId),
      startTime: zonedDateTimeToIso(editForm.startTime, timezone),
      deadlineTime: zonedDateTimeToIso(editForm.deadlineTime, timezone),
    }
    if (editForm.remindAt !== initialRemindAt.value) payload.remindAt = zonedDateTimeToIso(editForm.remindAt, timezone)
    await store.request(`/team-tasks/${task.value.id}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
    editOpen.value = false
    await Promise.all([loadDetail(), store.loadAll(), store.loadTeamTasks(task.value.teamId)])
    store.notify('任务已更新')
  } catch (e: any) { store.notify(e.message || '更新失败') }
}

async function handleTaskAction(action: 'cancel' | 'restore' | 'resubmit-approval') {
  if (!task.value) return
  const teamId = task.value.teamId
  try {
    await store.request(`/team-tasks/${props.id}/${action}`, { method: 'POST' })
    await Promise.all([loadDetail(), store.loadAll(), store.loadTeamTasks(teamId)])
    const messages = {
      cancel: '任务已取消',
      restore: '任务已恢复',
      'resubmit-approval': '任务已重新提交审批',
    }
    store.notify(messages[action])
  } catch (e: any) { store.notify(e.message || '操作失败') }
}

async function reviewApproval(action: 'approve' | 'reject-approval') {
  if (!task.value?.canReview || task.value.status !== 'pending_approval' || reviewSaving.value) return
  if (action === 'approve' && hasVacancies.value) {
    store.notify('请先为已移除的执行人补位')
    return
  }
  if (action === 'reject-approval' && !window.confirm('确认拒绝这项任务安排？')) return
  reviewSaving.value = true
  try {
    const teamId = task.value.teamId
    await store.request(`/team-tasks/${props.id}/${action}`, { method: 'POST' })
    await Promise.all([loadDetail(), store.loadAll(), store.loadTeamTasks(teamId)])
    store.notify(action === 'approve' ? '任务审批已通过' : '任务审批已拒绝')
  } catch (e: any) {
    store.notify(e.message || '审批操作失败')
  } finally {
    reviewSaving.value = false
  }
}

function goReassign(assignee: TeamTaskAssignee) {
  router.push(`/tasks/${props.id}/assignees/${assignee.assigneeId}/reassign`)
}

function goFillVacancy(candidate: TeamTaskReassignmentCandidate) {
  router.push(`/tasks/${props.id}/assignees/${candidate.assigneeId}/reassign`)
}

function goCorrectStatus(assignee: TeamTaskAssignee) {
  router.push(`/tasks/${props.id}/assignees/${assignee.assigneeId}/status`)
}

function handleDelete() {
  if (!task.value) return
  if (!canDeleteTeamTask(task.value)) {
    store.notify('已完成任务不能删除')
    return
  }
  if (!confirm('确认删除此任务？')) return
  store.request(`/team-tasks/${props.id}`, { method: 'DELETE' }).then(() => {
    store.notify('任务已删除')
    router.push('/tasks')
  }).catch((e: any) => store.notify(e.message))
}

function goBack() {
  router.push('/tasks')
}

onMounted(loadDetail)
</script>

<template>
  <section class="list-page">
    <div class="page-topbar" style="padding:0">
      <div>
        <button @click="goBack" style="border:0;background:transparent;padding:0;color:#2f80ed;font-weight:700">← 返回任务列表</button>
      </div>
    </div>

    <div v-if="loading" class="hint" style="text-align:center;padding:60px 0">加载中...</div>

    <div v-else-if="!task" class="hint" style="text-align:center;padding:60px 0">任务未找到</div>

    <section v-else class="form-card" style="margin-top:16px">
      <div class="section-head">
        <div><h2>{{ task.title }}</h2><p class="muted">{{ task.groupName || '未分组' }}</p></div>
        <button v-if="isCreatorOrAdmin" class="primary" @click="startEdit">编辑任务</button>
      </div>

      <div style="display:grid;gap:16px;margin-top:20px">
        <div v-if="task.description">
          <span class="muted">描述：</span>
          <p>{{ task.description }}</p>
          <button v-if="task.description" type="button" class="ai-btn" style="margin-top:8px" @click="aiOptimizeDesc" :disabled="aiOptimizing">
            <Sparkles :size="15" />{{ aiOptimizing ? '优化中...' : 'AI 优化描述' }}
          </button>
          <p v-if="optimizedDesc" style="margin-top:8px;padding:8px;background:#f0f9ff;border-radius:6px;border:1px solid #bae6fd">
            <strong>AI 建议：</strong>{{ optimizedDesc }}
          </p>
          <button v-if="optimizedDesc && isCreatorOrAdmin && !optimizedDesc.startsWith('优化失败')" type="button" class="primary" style="margin-top:8px" @click="applyOptimizedDescription">应用并保存</button>
        </div>
        <div>
          <span class="muted">团队：</span>
          <span>{{ task.teamName }}</span>
        </div>
        <div>
          <span class="muted">创建者：</span>
          <span>{{ task.creatorName }}</span>
        </div>
        <div>
          <span class="muted">开始时间：</span>
          <span>{{ formatTime(task.startTime) }}</span>
        </div>
        <div>
          <span class="muted">截止时间：</span>
          <span>{{ formatTime(task.deadlineTime) }}</span>
          <span v-if="['active', 'unassigned'].includes(task.status)" :class="['tag', urgency(task.deadlineTime)]" style="margin-left:8px">{{ countdown(task.deadlineTime) }}</span>
        </div>
        <div>
          <span class="muted">整体状态：</span>
          <span :class="['tag', taskStatusClass]">{{ statusLabel(task.status) }}</span>
        </div>
        <div v-if="task.pendingReminders?.length">
          <span class="muted">下次提醒：</span><span>{{ formatTime(task.pendingReminders[0].remindAt) }}</span>
        </div>
        <div v-if="task.assignStatus === 'completed' && (task.completedAt || task.completedFatigueLevel)">
          <span class="muted">我的完成记录：</span>
          <span v-if="task.completedAt">完成于 {{ formatTime(task.completedAt) }}</span>
          <span v-if="task.completedFatigueLevel" class="muted" style="margin-left:8px">疲劳 {{ task.completedFatigueLevel }} · {{ fatigueNames[task.completedFatigueLevel - 1] }}{{ task.completedFatigueWeight ? ` · ${task.completedFatigueWeight} 点` : '' }}</span>
          <span v-else class="muted" style="margin-left:8px">未记录疲劳快照</span>
        </div>
      </div>

      <!-- 当前执行人的待处理操作 -->
      <div v-if="isActionable && ['pending', 'accepted'].includes(myAssignStatus)" class="form-actions" style="margin-top:20px">
        <button v-if="myAssignStatus === 'pending'" class="primary" @click="handleMyAction('accept')">接受任务</button>
        <button v-if="myAssignStatus === 'accepted'" class="primary" @click="handleComplete">完成任务</button>
        <button @click="handleMyAction('reject')">拒绝</button>
      </div>

      <!-- 执行人状态卡片 -->
      <div style="margin-top:24px">
        <h3>执行人</h3>
        <article
          v-for="a in task.assignees"
          :key="a.assigneeId"
          class="task-card"
          style="margin-top:10px;grid-template-columns:minmax(0,1fr) auto auto auto auto"
        >
          <div>
            <strong>{{ a.nickname }}</strong>
            <small>用户ID: {{ a.userId }}</small>
          </div>
          <span :class="['tag', a.assignStatus === 'accepted' ? 'blue' : a.assignStatus === 'rejected' ? 'danger' : a.assignStatus === 'completed' ? 'blue' : 'warning']">
            {{ statusLabel(a.assignStatus) }}
          </span>
          <span v-if="a.isCurrent" class="tag blue">当前有效</span>
          <span v-else class="tag">历史记录</span>
          <small>第 {{ a.assignRound }} 轮<span v-if="a.completedAt" class="muted"> · 完成于 {{ formatTime(a.completedAt) }}</span></small>
          <div class="top-actions">
            <!-- rejected 执行人的重新分配入口 -->
            <button v-if="a.isCurrent && a.assignStatus === 'rejected' && isCreatorOrAdmin && !['completed', 'cancelled', 'approval_rejected'].includes(task.status)" @click="goReassign(a)">重新分配</button>
            <!-- 创建者/管理员修正执行人状态 -->
            <button v-if="canCorrectTeamTaskAssignee(task) && a.isCurrent && isCreatorOrAdmin" @click="goCorrectStatus(a)">修正状态</button>
          </div>
        </article>
        <div v-if="isTeamAdmin && hasReassignmentCandidates" class="form-actions" style="margin-top:12px">
          <button v-for="candidate in task.reassignmentCandidates" :key="candidate.assigneeId" @click="goFillVacancy(candidate)">为 {{ candidate.nickname }} 补位</button>
        </div>
        <p v-if="task.status === 'pending_approval' && task.canReview && hasVacancies" class="hint" style="margin-top:10px">需先补齐执行人，才能通过审批。</p>
      </div>

      <div style="margin-top:24px">
        <h3>操作时间轴</h3>
        <div style="position:relative;margin-top:10px;padding-left:20px">
          <div style="position:absolute;left:7px;top:6px;bottom:6px;width:2px;background:#edf1f6"></div>
          <article v-for="event in (task.events || []).slice().reverse()" :key="event.id" style="position:relative;display:grid;grid-template-columns:14px minmax(0,1fr);gap:8px;padding:8px 0">
            <span style="width:8px;height:8px;border-radius:50%;background:#2f80ed;box-shadow:0 0 0 3px #eaf3ff;margin-top:4px"></span>
            <div>
              <span class="tag" style="font-size:10px;background:#eaf3ff;color:#2f80ed;margin-right:6px">{{ eventTypeLabels[event.eventType] || event.eventType }}</span>
              <strong style="font-size:13px">{{ event.actorName || '系统' }}</strong>
              <span style="font-size:12px;color:#64748b;margin-left:4px">{{ event.content }}</span>
              <span style="display:block;font-size:11px;color:#94a3b8;margin-top:2px">{{ formatTime(event.createdAt) }}</span>
            </div>
          </article>
        </div>
        <p v-if="!task.events?.length" class="muted" style="padding:12px 0">暂无操作记录</p>
      </div>

      <!-- 创建者/管理员操作 -->
      <div v-if="isCreatorOrAdmin" class="form-actions" style="margin-top:24px">
        <button v-if="task.status === 'pending_approval' && task.canReview" :disabled="reviewSaving || hasVacancies" :title="hasVacancies ? '请先补齐执行人' : '通过审批'" class="primary" @click="reviewApproval('approve')">通过审批</button>
        <button v-if="task.status === 'pending_approval' && task.canReview" :disabled="reviewSaving" @click="reviewApproval('reject-approval')">拒绝审批</button>
        <button v-if="task.status === 'approval_rejected'" class="primary" @click="handleTaskAction('resubmit-approval')">重新提交审批</button>
        <button v-if="canCancelTask" @click="handleTaskAction('cancel')">取消任务</button>
        <button v-if="task.status === 'cancelled' && isTeamAdmin" @click="handleTaskAction('restore')">恢复任务</button>
        <button v-if="canDeleteTeamTask(task)" @click="handleDelete">删除任务</button>
      </div>
    </section>

    <div v-if="editOpen" class="modal-backdrop" @click.self="editOpen = false">
      <section class="modal-panel" style="max-width:560px">
        <div class="modal-head"><h2>编辑团队任务</h2><button class="modal-close" aria-label="关闭" @click="editOpen = false">×</button></div>
        <form @submit.prevent="saveEdit">
          <label>标题<input v-model="editForm.title" maxlength="200" /></label>
          <label>说明<textarea v-model="editForm.description" rows="5" placeholder="补充目标、交付物或注意事项"></textarea></label>
          <label>分组<select v-model="editForm.groupId"><option v-for="group in taskGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select></label>
          <label>开始时间<input v-model="editForm.startTime" type="datetime-local" /></label>
          <label>截止时间<input v-model="editForm.deadlineTime" type="datetime-local" /></label>
          <label>提醒时间<input v-model="editForm.remindAt" type="datetime-local" /><small class="muted">清空后保存会取消所有执行人的未发送提醒。</small></label>
          <div class="form-actions"><button type="button" @click="editOpen = false">取消</button><button class="primary">保存</button></div>
        </form>
      </section>
    </div>

    <div v-if="completeOpen" class="modal-backdrop" @click.self="completeOpen = false">
      <section class="modal-panel" style="max-width: 480px;">
        <div class="modal-head"><h2>完成团队任务</h2><button class="modal-close" aria-label="关闭" @click="completeOpen = false">✕</button></div>
        <p class="muted" style="margin-bottom: 12px;">「{{ task?.title }}」完成后，请选择这项任务给你带来的实际疲劳程度。</p>
        <ScheduleLevelControl v-model="completingFatigueLevel" label="完成疲劳度" :labels="fatigueNames" :weights="store.fatigueProfile?.weights" />
        <div class="form-actions" style="margin-top:18px">
          <button @click="completeOpen = false">取消</button>
          <button class="primary" :disabled="completingSubmitting || completingFatigueLevel === null" @click="submitComplete">{{ completingSubmitting ? '完成中...' : '确认完成' }}</button>
        </div>
      </section>
    </div>
  </section>
</template>
