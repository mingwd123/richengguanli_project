<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, countdown, timeTypeLabel, primaryTime } from '../utils/helpers'

const router = useRouter()
const store = useAppStore()

const monthDays = computed(() => store.monthDays)

function selectDate(day: number | '') {
  if (day === '') return
  const month = new Date().getMonth() + 1
  const year = new Date().getFullYear()
  const dateStr = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  store.selectedDate = dateStr
}

const selectedDayItems = computed(() => {
  if (!store.selectedDate) return []
  return store.calendarItems.filter((item: any) => primaryTime(item).startsWith(store.selectedDate))
})

const weekDays = ['一', '二', '三', '四', '五', '六', '日']

function goDetail(item: any) {
  router.push(item.sourceType === 'team_task' ? `/tasks/${item.id}` : `/schedules/${item.id}`)
}
</script>

<template>
  <section class="split-layout">
    <section class="calendar-card">
      <h2>本月日历</h2>
      <div class="week-row" style="margin-top: 14px;">
        <span v-for="w in weekDays" :key="w">{{ w }}</span>
      </div>
      <div class="month-grid" style="margin-top: 8px;">
        <article
          v-for="(d, i) in monthDays"
          :key="i"
          :class="['date-cell', { today: d.today }]"
          @click="selectDate(d.day)"
        >
          <strong>{{ d.day }}</strong>
          <small v-if="d.items.length">{{ d.items.length }} 项</small>
        </article>
      </div>
    </section>

    <aside class="detail-panel">
      <h2>{{ store.selectedDate ? store.selectedDate + ' 日程' : '点击日期查看' }}</h2>
      <article
        v-for="item in selectedDayItems"
        :key="`${item.sourceType}-${item.id}`"
        class="mini-row"
        style="cursor: pointer;"
        @click="goDetail(item)"
      >
        <strong>{{ item.title }}</strong>
        <small>{{ item.sourceType === 'team_task' ? '团队任务' : timeTypeLabel(item.timeType) }} - {{ countdown(primaryTime(item)) }}</small>
      </article>
      <p v-if="store.selectedDate && selectedDayItems.length === 0" class="muted" style="padding: 20px; text-align: center;">
        该日期暂无安排
      </p>
      <p v-if="!store.selectedDate" class="muted" style="padding: 20px; text-align: center;">
        请点击日历中的日期查看日程
      </p>
    </aside>
  </section>
</template>
