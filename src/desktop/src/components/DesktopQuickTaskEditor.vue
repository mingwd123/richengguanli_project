<script setup lang="ts">
import { computed } from 'vue'
import { Save } from 'lucide-vue-next'
import type { TaskGroup } from '@web/types'
import type { QuickTaskEditForm } from './desktopQuickTypes'
import DesktopQuickReminderPicker from './DesktopQuickReminderPicker.vue'

const props = defineProps<{
  groups: TaskGroup[]
  busy: boolean
  reminderPresets: number[]
  timezone: string
}>()

const emit = defineEmits<{
  submit: []
  error: [message: string]
}>()

const form = defineModel<QuickTaskEditForm>({ required: true })
const reminderBaseTime = computed(() => form.value.deadlineTime || form.value.startTime)
const reminderBaseLabel = computed(() => form.value.deadlineTime ? '截止时间' : '开始时间')
</script>

<template>
  <form class="quick-editor" @submit.prevent="emit('submit')">
    <label class="quick-editor-field quick-editor-title"><span>标题</span><input v-model="form.title" maxlength="200" autofocus /></label>
    <label class="quick-editor-field"><span>团队分组</span><select v-model="form.groupId"><option value="" disabled>选择分组</option><option v-for="group in groups" :key="group.id" :value="String(group.id)">{{ group.name }}</option></select></label>
    <div class="quick-editor-time-grid">
      <label class="quick-editor-field"><span>开始时间</span><input v-model="form.startTime" type="datetime-local" /></label>
      <label class="quick-editor-field"><span>截止时间</span><input v-model="form.deadlineTime" type="datetime-local" /></label>
    </div>
    <DesktopQuickReminderPicker v-model="form.remindAt" :base-time="reminderBaseTime" :base-label="reminderBaseLabel" :presets="props.reminderPresets" :timezone="props.timezone" :disabled="busy" @error="emit('error', $event)" />
    <label class="quick-editor-field"><span>说明 <small>可选</small></span><textarea v-model="form.description" rows="5" placeholder="补充目标、交付物或注意事项"></textarea></label>
    <footer class="quick-editor-actions"><button class="primary" :disabled="busy"><Save :size="16" />{{ busy ? '保存中...' : '保存修改' }}</button></footer>
  </form>
</template>
