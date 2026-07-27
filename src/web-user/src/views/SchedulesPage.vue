<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, primaryTime, timeTypeLabel, statusLabel, countdown, urgency, groupByTaskState, isOverdue } from '../utils/helpers'

const router = useRouter()
const store = useAppStore()
const collapsedGroups = ref<string[]>([])
const scheduleGroups = computed(() => groupByTaskState(store.schedules, schedule => schedule.groupName))

function toggleGroup(name: string) {
  collapsedGroups.value = collapsedGroups.value.includes(name)
    ? collapsedGroups.value.filter(value => value !== name)
    : [...collapsedGroups.value, name]
}

function goDetail(id: number) {
  router.push(`/schedules/${id}`)
}

function handleAction(item: any, action: string) {
  store.setScheduleStatus(item, action)
}

function handleDelete(id: number) {
  store.deleteSchedule(id)
}
</script>

<template>
  <section class="list-page">
    <div class="filter-bar">
      <button class="primary" @click="store.openScheduleModal()">新建日程</button>
    </div>

    <section class="table-card">
      <section v-for="group in scheduleGroups" :key="group.name" class="group-block">
        <button class="group-title" @click="toggleGroup(group.name)">
          <span>{{ collapsedGroups.includes(group.name) ? '▸' : '▾' }} {{ group.name }}</span>
          <em>{{ group.items.filter(item => item.status === 'pending').length }}</em>
        </button>
        <div v-if="!collapsedGroups.includes(group.name)">
          <article v-for="s in group.items" :key="s.id" :class="['table-row', { overdue: isOverdue(s) }]" style="cursor:pointer" @click="goDetail(s.id)">
            <div>
              <strong>{{ s.title }}</strong>
              <small>{{ s.groupName }} - {{ timeTypeLabel(s.timeType) }}</small>
            </div>
            <span :class="['tag', s.status === 'completed' ? 'blue' : s.status === 'cancelled' ? 'danger' : 'warning']">{{ statusLabel(s.status) }}</span>
            <span :class="{ 'overdue-text': isOverdue(s) }">{{ isOverdue(s) ? countdown(primaryTime(s)) : formatTime(primaryTime(s)) }}</span>
            <div class="top-actions" @click.stop>
              <button v-if="s.status === 'pending'" @click="handleAction(s, 'complete')">完成</button>
              <button v-if="s.status === 'completed'" @click="handleAction(s, 'uncomplete')">恢复</button>
              <button v-if="s.status === 'pending'" @click="handleAction(s, 'cancel')">取消</button>
              <button @click="handleDelete(s.id)">删除</button>
            </div>
          </article>
        </div>
      </section>
      <p v-if="!store.schedules.length" class="hint" style="text-align:center;padding:40px 0">暂无日程</p>
    </section>

    <!-- 新建日程弹窗 -->
    <div v-if="store.scheduleModalOpen" class="modal-backdrop" @click.self="store.closeScheduleModal()">
      <section class="modal-panel">
        <div class="modal-head">
          <h2>新建日程</h2>
          <button class="modal-close" @click="store.closeScheduleModal()">✕</button>
        </div>
        <form @submit.prevent="store.createSchedule()">
          <label>
            标题
            <input v-model="store.scheduleForm.title" />
          </label>
          <label>
            模块
            <select v-model="store.scheduleForm.groupId">
              <option v-for="group in store.taskGroups" :key="group.id" :value="group.id">{{ group.name }}</option>
            </select>
          </label>
          <label>
            类型
            <select v-model="store.scheduleForm.timeType">
              <option value="point_event">安排事项</option>
              <option value="deadline_task">待办任务</option>
              <option value="duration_task">时间段任务</option>
            </select>
          </label>
          <label v-if="store.scheduleForm.timeType === 'point_event'">
            发生时间
            <input v-model="store.scheduleForm.startTime" type="datetime-local" />
          </label>
          <label v-if="store.scheduleForm.timeType === 'deadline_task'">
            截止时间
            <input v-model="store.scheduleForm.deadlineTime" type="datetime-local" />
          </label>
          <template v-if="store.scheduleForm.timeType === 'duration_task'">
            <label>
              开始时间
              <input v-model="store.scheduleForm.startTime" type="datetime-local" />
            </label>
            <label>
              结束时间
              <input v-model="store.scheduleForm.endTime" type="datetime-local" />
            </label>
          </template>
          <div class="form-actions">
            <button type="button" @click="store.closeScheduleModal()">取消</button>
            <button class="primary" :disabled="store.loading">保存</button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>
