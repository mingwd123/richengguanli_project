<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, countdown, urgency, statusLabel } from '../utils/helpers'
import type { TeamTask, TeamTaskAssignee } from '../types'

const props = defineProps<{ id: string }>()
const router = useRouter()
const store = useAppStore()

const task = ref<TeamTask | null>(null)
const loading = ref(false)

const isCreatorOrAdmin = computed(() => {
  if (!task.value) return false
  const myProfile = store.profile
  if (!myProfile) return false
  return task.value.creatorId === myProfile.id
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

async function handleReassign(assignee: TeamTaskAssignee) {
  const newUserId = prompt(`为 ${assignee.nickname} 重新分配执行人，请输入新的用户ID：`)
  if (!newUserId) return
  try {
    await store.request(`/team-tasks/${props.id}/reassign`, {
      method: 'POST',
      body: JSON.stringify({ oldAssigneeUserId: assignee.userId, newAssigneeUserId: Number(newUserId) })
    })
    store.notify('已重新分配')
    await loadDetail()
  } catch (e: any) {
    store.notify(e.message)
  }
}

async function handleCorrectStatus(assignee: TeamTaskAssignee) {
  const newStatus = prompt(`修正 ${assignee.nickname} 的执行状态（pending / accepted / rejected / completed）：`, assignee.assignStatus)
  if (!newStatus) return
  try {
    await store.request(`/team-tasks/${props.id}/assignees/${assignee.userId}/status`, {
      method: 'PUT',
      body: JSON.stringify({ assignStatus: newStatus })
    })
    store.notify('状态已修正')
    await loadDetail()
  } catch (e: any) {
    store.notify(e.message)
  }
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
            <button v-if="a.assignStatus === 'rejected' && isCreatorOrAdmin" @click="handleReassign(a)">重新分配</button>
            <!-- 创建者/管理员修正执行人状态 -->
            <button v-if="isCreatorOrAdmin" @click="handleCorrectStatus(a)">修正状态</button>
          </div>
        </article>
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
