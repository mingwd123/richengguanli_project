<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { Sparkles } from 'lucide-vue-next'
import { formatTime, countdown, urgency, statusLabel } from '../utils/helpers'
import type { TeamTask, TeamTaskAssignee, TaskGroup } from '../types'

const eventTypeLabels: Record<string, string> = {
  created: '创建', assigned: '分配', accepted: '接受', rejected: '拒绝',
  completed: '完成', cancelled: '取消', restored: '恢复', reassigned: '重新分配',
  time_updated: '修改时间', status_corrected: '修正状态'
}

const props = defineProps<{ id: string }>()
const router = useRouter()
const store = useAppStore()

const task = ref<TeamTask | null>(null)
const loading = ref(false)
const aiOptimizing = ref(false)
const optimizedDesc = ref('')
const editOpen = ref(false)
const taskGroups = ref<TaskGroup[]>([])
const editForm = reactive({ title: '', description: '', groupId: '', startTime: '', deadlineTime: '', remindAt: '' })
const initialRemindAt = ref('')
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
  if (!task.value || !store.profile) return false
  const team = store.teams.find(t => t.id === task.value?.teamId)
  return task.value.creatorId === store.profile.id || team?.myRole === 'owner' || team?.myRole === 'admin'
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
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.slice(0, 16)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
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
    const payload: Record<string, any> = {
      title: editForm.title.trim(),
      description: editForm.description,
      groupId: Number(editForm.groupId),
      startTime: editForm.startTime ? new Date(editForm.startTime).toISOString() : '',
      deadlineTime: editForm.deadlineTime ? new Date(editForm.deadlineTime).toISOString() : '',
    }
    if (editForm.remindAt !== initialRemindAt.value) payload.remindAt = editForm.remindAt ? new Date(editForm.remindAt).toISOString() : ''
    await store.request(`/team-tasks/${task.value.id}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
    editOpen.value = false
    await Promise.all([loadDetail(), store.loadAll(), store.loadTeamTasks(task.value.teamId)])
    store.notify('任务已更新')
  } catch (e: any) { store.notify(e.message || '更新失败') }
}

function handleTaskAction(action: string) {
  if (!task.value) return
  const myTask = store.myTasks.find(t => String(t.id) === props.id)
  if (myTask) {
    store.taskAction(myTask, action).then(() => loadDetail())
  } else {
    store.request(`/team-tasks/${props.id}/${action}`, { method: 'POST' }).then(() => {
      loadDetail()
      store.notify('操作成功')
    }).catch((e: any) => store.notify(e.message))
  }
}

function goReassign(assignee: TeamTaskAssignee) {
  router.push(`/tasks/${props.id}/assignees/${assignee.userId}/reassign`)
}

function goCorrectStatus(assignee: TeamTaskAssignee) {
  router.push(`/tasks/${props.id}/assignees/${assignee.userId}/status`)
}

function handleDelete() {
  if (!task.value) return
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
          <span v-if="task.status === 'active'" :class="['tag', urgency(task.deadlineTime)]" style="margin-left:8px">{{ countdown(task.deadlineTime) }}</span>
        </div>
        <div>
          <span class="muted">整体状态：</span>
          <span :class="['tag', task.status === 'completed' ? 'blue' : task.status === 'cancelled' || task.status === 'all_rejected' ? 'danger' : 'warning']">{{ statusLabel(task.status) }}</span>
        </div>
        <div v-if="task.pendingReminders?.length">
          <span class="muted">下次提醒：</span><span>{{ formatTime(task.pendingReminders[0].remindAt) }}</span>
        </div>
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
          <small>第 {{ a.assignRound }} 轮</small>
          <div class="top-actions">
            <!-- rejected 执行人的重新分配入口 -->
            <button v-if="a.assignStatus === 'rejected' && isCreatorOrAdmin" @click="goReassign(a)">重新分配</button>
            <!-- 创建者/管理员修正执行人状态 -->
            <button v-if="isCreatorOrAdmin" @click="goCorrectStatus(a)">修正状态</button>
          </div>
        </article>
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
        <button v-if="task.status === 'active'" @click="handleTaskAction('cancel')">取消任务</button>
        <button v-if="task.status === 'cancelled'" @click="handleTaskAction('restore')">恢复任务</button>
        <button @click="handleDelete">删除任务</button>
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
  </section>
</template>
