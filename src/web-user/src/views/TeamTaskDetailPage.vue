<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, countdown, urgency, statusLabel } from '../utils/helpers'
import type { TeamTask, TeamTaskAssignee } from '../types'

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
      <h2>{{ task.title }}</h2>

      <div style="display:grid;gap:16px;margin-top:20px">
        <div v-if="task.description">
          <span class="muted">描述：</span>
          <p>{{ task.description }}</p>
          <button v-if="task.description" type="button" class="ai-btn" style="margin-top:8px" @click="aiOptimizeDesc" :disabled="aiOptimizing">
            {{ aiOptimizing ? '优化中...' : '🤖 AI 优化描述' }}
          </button>
          <p v-if="optimizedDesc" style="margin-top:8px;padding:8px;background:#f0f9ff;border-radius:6px;border:1px solid #bae6fd">
            <strong>AI 建议：</strong>{{ optimizedDesc }}
          </p>
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
  </section>
</template>
