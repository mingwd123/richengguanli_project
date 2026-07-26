<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '../stores/app'
import { formatTime, countdown, urgency, timeTypeLabel } from '../utils/helpers'

const store = useAppStore()

const upcoming = computed(() => store.upcoming)
const timelineItems = computed(() => store.timelineItems.slice(0, 8))
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

    <div class="home-content">
      <section class="group-panel">
        <div class="section-head">
          <h2>近期任务</h2>
        </div>
        <article
          v-for="item in upcoming"
          :key="`${item.sourceType}-${item.id}`"
          class="task-card"
        >
          <div>
            <strong>{{ item.title }}</strong>
            <small>{{ item.sourceLabel }} - {{ formatTime(item.sortAt) }}</small>
          </div>
          <span :class="['tag', urgency(item.sortAt)]">{{ countdown(item.sortAt) }}</span>
        </article>
        <p v-if="upcoming.length === 0" class="muted" style="padding: 20px; text-align: center;">暂无近期任务</p>
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
