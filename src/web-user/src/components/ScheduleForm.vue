<script setup lang="ts">
import { useAppStore } from '../stores/app'
import { labels } from '../utils/labels'

const store = useAppStore()
</script>

<template>
  <form @submit.prevent="store.createSchedule()">
    <label>
      {{ labels.title }}
      <input v-model="store.scheduleForm.title" />
    </label>
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
