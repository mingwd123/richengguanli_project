<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, countdown, urgency, timeTypeLabel, groupByTaskState } from '../utils/helpers'

const store = useAppStore()
const router = useRouter()

const timelineItems = computed(() => store.timelineItems.slice(0, 8))
const scheduleModules = computed(() => groupByTaskState(
  store.schedules.filter(schedule => !['completed', 'cancelled'].includes(schedule.status)),
  schedule => schedule.groupName || '未分组'
))

function goSchedule(id: number) {
  router.push(`/schedules/${id}`)
}

const aiPlan = ref('')
const aiLoadingPlan = ref(false)
async function loadAiPlan() {
  aiLoadingPlan.value = true
  try {
    const result = await store.aiRequest('/home/daily-plan', {})
    aiPlan.value = result.suggestion || (result as any).rawText || ''
  } catch (e: any) {
    store.notify('AI 计划加载失败: ' + (e.message || '服务不可用'))
  } finally { aiLoadingPlan.value = false }
}
</script>

<template>
  <section class="home-layout">
    <div class="stats-row">
      <article class="stat-card">
        <span>待办日程</span>
        <strong>{{ store.pendingScheduleCount }}</strong>
      </article>
      <article class="stat-card">
        <span>团队任务</span>
        <strong>{{ store.activeTaskCount }}</strong>
      </article>
      <article class="stat-card">
        <span>未读通知</span>
        <strong>{{ store.today.unreadNotificationCount }}</strong>
      </article>
    </div>

    <div class="ai-plan-section" v-if="aiPlan">
      <div class="ai-plan-header"><span>🤖 AI 今日计划</span><button class="plain-button" @click="loadAiPlan" :disabled="aiLoadingPlan" style="font-size:12px">{{ aiLoadingPlan ? '生成中...' : '刷新' }}</button></div>
      <p class="ai-plan-content">{{ aiPlan }}</p>
    </div>
    <div class="ai-plan-section" v-else style="cursor:pointer;background:#f0f9ff;border:1px dashed #93c5fd" @click="loadAiPlan">
      <p class="muted" style="text-align:center;padding:12px">{{ aiLoadingPlan ? 'AI 正在思考今天的计划...' : '🤖 点击生成 AI 今日计划建议' }}</p>
    </div>

    <div class="home-content">
      <section class="group-panel">
        <div class="section-head">
          <h2>日程模块</h2>
        </div>
        <section v-for="module in scheduleModules" :key="module.name" class="home-module">
          <div class="module-title">
            <strong>{{ module.name }}</strong>
            <span>{{ module.items.filter(item => item.status === 'pending').length }} 项待办</span>
          </div>
          <article
            v-for="schedule in module.items.slice(0, 3)"
            :key="schedule.id"
            :class="['task-card', { overdue: urgency(schedule.deadlineTime || schedule.endTime || schedule.startTime) === 'danger' }]"
            @click="goSchedule(schedule.id)"
          >
            <div>
              <strong>{{ schedule.title }}</strong>
              <small>{{ formatTime(schedule.deadlineTime || schedule.endTime || schedule.startTime) }}</small>
            </div>
            <span :class="['tag', urgency(schedule.deadlineTime || schedule.endTime || schedule.startTime)]">{{ countdown(schedule.deadlineTime || schedule.endTime || schedule.startTime) }}</span>
          </article>
          <p v-if="module.items.length > 3" class="muted module-more">另有 {{ module.items.length - 3 }} 项日程，请前往日程页查看</p>
        </section>
        <p v-if="scheduleModules.length === 0" class="muted" style="padding: 20px; text-align: center;">暂无日程</p>
      </section>

      <aside class="timeline-panel">
        <div class="timeline-head">
          <div>
            <h2>时间轴</h2>
            <p>{{ store.timelineStats.today }} 今天 / {{ store.timelineStats.overdue }} 逾期</p>
          </div>
        </div>
        <div class="timeline-track">
          <article
            v-for="item in timelineItems"
            :key="`${item.sourceType}-${item.id}-${item.assignRound || 0}`"
            :class="['timeline-entry', item.kind]"
          >
            <div class="time-node">
              <span :class="['line-dot', urgency(item.sortAt)]"></span>
            </div>
            <div class="timeline-card">
              <div class="timeline-card-top">
                <span class="time-text">{{ formatTime(item.sortAt) }}</span>
                <span :class="['kind-chip', item.kind]">{{ timeTypeLabel(item.kind) }}</span>
              </div>
              <strong>{{ item.title }}</strong>
              <small>{{ item.sourceLabel }}</small>
            </div>
          </article>
        </div>
        <p v-if="timelineItems.length === 0" class="muted" style="padding: 30px; text-align: center;">暂无时间轴数据</p>
      </aside>
    </div>
  </section>
</template>
