<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { countdown, statusLabel, groupByTaskState, isOverdue } from '../utils/helpers'
import type { TeamMember } from '../types'

const router = useRouter()
const store = useAppStore()
const teamMembers = ref<TeamMember[]>([])
const collapsedGroups = ref<string[]>([])
const taskGroups = computed(() => groupByTaskState(store.myTasks, task => task.teamName || '团队任务'))

function toggleGroup(name: string) {
  collapsedGroups.value = collapsedGroups.value.includes(name)
    ? collapsedGroups.value.filter(value => value !== name)
    : [...collapsedGroups.value, name]
}

function goDetail(id: number) {
  router.push(`/tasks/${id}`)
}

const selectableMembers = computed(() => teamMembers.value.filter(member => member.status === 'active'))

watch(() => store.taskForm.teamId, async teamId => {
  store.taskForm.assigneeUserIds = []
  teamMembers.value = []
  if (!teamId) return
  try {
    const data = await store.request<{ list: TeamMember[] }>(`/teams/${teamId}/members`)
    teamMembers.value = data.list || []
  } catch (e: any) {
    store.notify(e.message || '加载成员失败')
  }
}, { immediate: true })

function handleAction(task: any, action: string) {
  store.taskAction(task, action)
}
</script>

<template>
  <section class="split-layout">
    <section class="list-card">
      <div class="section-head">
        <h2>团队任务</h2>
      </div>

      <!-- 创建任务表单 -->
      <form class="inline-form" @submit.prevent="store.createTask()">
        <select v-model="store.taskForm.teamId">
          <option value="">选择团队</option>
          <option v-for="team in store.teams" :key="team.id" :value="team.id">{{ team.name }}</option>
        </select>
        <input v-model="store.taskForm.title" placeholder="任务标题" />
        <input v-model="store.taskForm.deadlineTime" type="datetime-local" />
        <div class="assignee-picker">
          <label v-for="member in selectableMembers" :key="member.userId" class="check-option">
            <input v-model="store.taskForm.assigneeUserIds" type="checkbox" :value="member.userId" />
            <span>{{ member.nickname }}</span>
          </label>
          <span v-if="store.taskForm.teamId && selectableMembers.length === 0" class="muted">暂无可选成员</span>
        </div>
        <button class="primary">创建</button>
      </form>

      <!-- 我的任务列表 -->
      <section v-for="group in taskGroups" :key="group.name" class="group-block">
        <button class="group-title" @click="toggleGroup(group.name)">
          <span>{{ collapsedGroups.includes(group.name) ? '▸' : '▾' }} {{ group.name }}</span>
          <em>{{ group.items.filter(item => item.assignStatus !== 'completed' && item.status === 'active').length }}</em>
        </button>
        <div v-if="!collapsedGroups.includes(group.name)">
          <article
            v-for="task in group.items"
            :key="task.assigneeId || task.id"
            :class="['task-card', { overdue: isOverdue(task) }]"
            style="cursor:pointer"
            @click="goDetail(task.id)"
          >
            <div>
              <strong>{{ task.title }}</strong>
              <small :class="{ 'overdue-text': isOverdue(task) }">{{ task.teamName }} - {{ countdown(task.deadlineTime) }}</small>
            </div>
            <span :class="['tag', task.assignStatus === 'accepted' ? 'blue' : task.assignStatus === 'rejected' ? 'danger' : 'warning']">
              {{ statusLabel(task.assignStatus || task.status) }}
            </span>
            <div class="top-actions" @click.stop>
              <button v-if="task.assignStatus === 'pending'" @click="handleAction(task, 'accept')">接受</button>
              <button v-if="task.assignStatus === 'pending'" @click="handleAction(task, 'reject')">拒绝</button>
              <button v-if="task.assignStatus === 'accepted'" @click="handleAction(task, 'complete')">完成</button>
            </div>
          </article>
        </div>
      </section>
      <p v-if="!store.myTasks.length" class="hint" style="text-align:center;padding:40px 0">暂无团队任务</p>
    </section>

    <aside class="detail-panel">
      <h2>任务说明</h2>
      <p class="muted">创建团队任务后，执行人需要接受任务才能开始执行。点击任务可查看详细信息和执行人状态。</p>
    </aside>
  </section>
</template>
