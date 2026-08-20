<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '../stores/app'
import { labels } from '../utils/labels'
import { getDisplayTimezone } from '../utils/helpers'
import ScheduleLevelControl from './ScheduleLevelControl.vue'
import ReminderShortcutPicker from './ReminderShortcutPicker.vue'

const store = useAppStore()
const urgencyNames = ['不紧急', '较低', '普通', '紧急', '非常紧急']
const fatigueNames = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']
const reminderPresets = computed(() => store.notificationPreferences.reminderPresetMinutes || [])
const userTimezone = computed(() => store.profile?.timezone || getDisplayTimezone())
const reminderBaseTime = computed(() => store.scheduleForm.timeType === 'deadline_task' ? store.scheduleForm.deadlineTime : store.scheduleForm.startTime)
const reminderBaseLabel = computed(() => store.scheduleForm.timeType === 'deadline_task' ? '截止时间' : '开始时间')
</script>

<template>
  <form @submit.prevent="store.createSchedule()">
    <label>
      {{ labels.title }}
      <input v-model="store.scheduleForm.title" />
    </label>
    <label>描述<textarea v-model="store.scheduleForm.description" rows="3"></textarea></label>
    <label>
      {{ labels.module }}
      <select v-model="store.scheduleForm.groupId">
        <option v-for="group in store.taskGroups" :key="group.id" :value="group.id">
          {{ group.name }}
        </option>
      </select>
    </label>
    <label>
      {{ labels.type }}
      <select v-model="store.scheduleForm.timeType">
        <option value="point_event">{{ labels.pointEvent }}</option>
        <option value="deadline_task">{{ labels.deadlineTask }}</option>
        <option value="duration_task">{{ labels.durationTask }}</option>
      </select>
    </label>
    <div class="level-form-grid">
      <ScheduleLevelControl v-model="store.scheduleForm.urgencyLevel" label="紧急度" :labels="urgencyNames" />
      <ScheduleLevelControl v-model="store.scheduleForm.fatigueLevel" label="预计疲劳度" :labels="fatigueNames" :weights="store.fatigueProfile?.weights" />
    </div>
    <label v-if="store.scheduleForm.timeType === 'point_event'">
      {{ labels.occurTime }}
      <input v-model="store.scheduleForm.startTime" type="datetime-local" />
    </label>
    <label v-if="store.scheduleForm.timeType === 'deadline_task'">
      {{ labels.deadlineTime }}
      <input v-model="store.scheduleForm.deadlineTime" type="datetime-local" />
    </label>
    <label v-if="store.scheduleForm.timeType === 'duration_task'">
      {{ labels.startTime }}
      <input v-model="store.scheduleForm.startTime" type="datetime-local" />
    </label>
    <ReminderShortcutPicker v-model="store.scheduleForm.remindAt" :base-time="reminderBaseTime" :base-label="reminderBaseLabel" :presets="reminderPresets" :timezone="userTimezone" @error="store.notify" />
    <label v-if="store.scheduleForm.timeType === 'duration_task'">
      {{ labels.endTime }}
      <input v-model="store.scheduleForm.endTime" type="datetime-local" />
    </label>
    <div class="form-actions">
      <button type="button" @click="store.closeScheduleModal()">{{ labels.cancel }}</button>
      <button class="primary">{{ labels.save }}</button>
    </div>
  </form>
</template>
