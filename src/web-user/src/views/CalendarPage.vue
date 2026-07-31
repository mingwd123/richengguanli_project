<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, countdown, timeTypeLabel, primaryTime, currentDateParts, occursOnDate } from '../utils/helpers'

const router = useRouter()
const store = useAppStore()

const monthDays = computed(() => store.monthDays)

function selectDate(day: number | '') {
  if (day === '') return
  const { month, year } = currentDateParts(store.profile?.timezone)
  const dateStr = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  store.selectedDate = dateStr
}

const selectedDayItems = computed(() => {
  if (!store.selectedDate) return []
  return store.calendarItems
    .filter((item: any) => occursOnDate(item, store.selectedDate, store.profile?.timezone))
    .sort((a: any, b: any) => {
      const aTime = primaryTime(a)
      const bTime = primaryTime(b)
      if (!aTime) return 1
      if (!bTime) return -1
      return new Date(aTime).getTime() - new Date(bTime).getTime()
    })
    .map((item: any) => ({
      ...item,
      typeLabel: item.sourceType === 'team_task' ? '团队任务' : timeTypeLabel(item.timeType),
      timeDisplay: formatTime(primaryTime(item)),
      countdownText: countdown(primaryTime(item))
    }))
})

const weekDays = ['一', '二', '三', '四', '五', '六', '日']

onMounted(() => {
  const { month, year } = currentDateParts(store.profile?.timezone)
  store.loadCalendar(year, month)
})

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
          <div class="date-cell-head">
            <strong>{{ d.day }}</strong>
            <small v-if="d.items.length">{{ d.items.length }} 项</small>
          </div>
        </article>
      </div>
    </section>

    <aside class="detail-panel">
      <h2>{{ store.selectedDate ? store.selectedDate + ' 日程' : '点击日期查看' }}</h2>
      <div v-if="selectedDayItems.length > 0" class="calendar-timeline">
        <div class="cal-timeline-axis"></div>
        <article
          v-for="item in selectedDayItems"
          :key="`${item.sourceType}-${item.id}`"
          :class="['cal-timeline-entry', item.sourceType === 'team_task' ? 'cal-entry-team' : 'cal-entry-schedule']"
          style="cursor: pointer;"
          @click="goDetail(item)"
          :title="`${item.title}\n${item.typeLabel}\n${item.sourceType === 'team_task' ? (item.teamName || '') : (item.groupName || '')}\n${item.timeDisplay}\n${item.countdownText}`"
        >
          <div class="cal-tl-left">
            <span class="cal-tl-time">{{ item.timeDisplay }}</span>
          </div>
          <div class="cal-tl-dot-wrap">
            <span :class="['cal-tl-dot', item.sourceType === 'team_task' ? 'cal-dot-team' : 'cal-dot-schedule']"></span>
          </div>
          <div :class="['cal-tl-card', item.sourceType === 'team_task' ? 'cal-card-team' : 'cal-card-schedule']">
            <div class="cal-tl-card-top">
              <span :class="['cal-type-chip', item.sourceType === 'team_task' ? 'chip-team' : 'chip-schedule']">{{ item.typeLabel }}</span>
              <span class="cal-source-name">{{ item.sourceType === 'team_task' ? (item.teamName || '团队任务') : (item.groupName || '个人日程') }}</span>
            </div>
            <strong class="cal-tl-title">{{ item.title }}</strong>
            <span :class="['cal-tl-countdown', { 'cal-overdue': item.countdownText.startsWith('已逾期') }]">{{ item.countdownText }}</span>
          </div>
        </article>
      </div>
      <p v-if="store.selectedDate && selectedDayItems.length === 0" class="muted" style="padding: 20px; text-align: center;">
        该日期暂无安排
      </p>
      <p v-if="!store.selectedDate" class="muted" style="padding: 20px; text-align: center;">
        请点击日历中的日期查看日程
      </p>
    </aside>
  </section>
</template>
