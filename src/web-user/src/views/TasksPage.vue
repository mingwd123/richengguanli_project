<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, countdown, urgency, statusLabel } from '../utils/helpers'

const router = useRouter()
const store = useAppStore()

function goDetail(id: number) {
  router.push(`/tasks/${id}`)
}

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
        <input v-model="store.taskForm.assigneeUserIds" placeholder="执行人用户 ID（逗号分隔）" />
        <button class="primary">创建</button>
      </form>

      <!-- 我的任务列表 -->
      <article
        v-for="task in store.myTasks"
        :key="task.assigneeId || task.id"
        class="task-card"
        style="cursor:pointer"
        @click="goDetail(task.id)"
      >
        <div>
          <strong>{{ task.title }}</strong>
          <small>{{ task.teamName }} - {{ countdown(task.deadlineTime) }}</small>
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
      <p v-if="!store.myTasks.length" class="hint" style="text-align:center;padding:40px 0">暂无团队任务</p>
    </section>

    <aside class="detail-panel">
      <h2>任务说明</h2>
      <p class="muted">创建团队任务后，执行人需要接受任务才能开始执行。点击任务可查看详细信息和执行人状态。</p>
    </aside>
  </section>
</template>
