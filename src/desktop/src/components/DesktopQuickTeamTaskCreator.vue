<script setup lang="ts">
import { computed } from 'vue'
import { ListTree, Save, Sparkles, UsersRound } from 'lucide-vue-next'
import type { TaskForm, TaskGroup, Team, TeamMember } from '@web/types'
import DesktopQuickReminderPicker from './DesktopQuickReminderPicker.vue'

const props = defineProps<{
  teams: Team[]
  groups: TaskGroup[]
  members: TeamMember[]
  busy: boolean
  loadingContext: boolean
  reminderPresets: number[]
  timezone: string
  aiBreakdownBusy: boolean
  aiOptimizeBusy: boolean
  aiError?: string
  aiNotice?: string
}>()

const emit = defineEmits<{
  submit: []
  teamChange: [teamId: string]
  error: [message: string]
  aiBreakdown: []
  aiOptimize: []
}>()

const form = defineModel<TaskForm>({ required: true })
const reminderBaseTime = computed(() => form.value.deadlineTime || form.value.startTime)
const reminderBaseLabel = computed(() => form.value.deadlineTime ? '截止时间' : '开始时间')
</script>

<template>
  <form class="quick-editor" @submit.prevent="emit('submit')">
    <div class="quick-editor-time-grid">
      <label class="quick-editor-field"><span>团队</span><select v-model="form.teamId" @change="emit('teamChange', form.teamId)"><option value="" disabled>选择团队</option><option v-for="team in teams" :key="team.id" :value="String(team.id)">{{ team.name }}</option></select></label>
      <label class="quick-editor-field"><span>分组</span><select v-model="form.groupId" :disabled="loadingContext || !form.teamId"><option value="" disabled>{{ loadingContext ? '加载中...' : '选择分组' }}</option><option v-for="group in groups" :key="group.id" :value="String(group.id)">{{ group.name }}</option></select></label>
    </div>

    <label class="quick-editor-field quick-editor-title"><span>标题</span><input v-model="form.title" maxlength="200" autofocus placeholder="需要团队完成什么？" /></label>
    <label class="quick-editor-field"><span>说明 <small>可选</small></span><textarea v-model="form.description" rows="4" placeholder="补充目标、交付物或注意事项"></textarea></label>
    <div class="quick-ai-parse-row quick-team-ai-actions">
      <button type="button" class="quick-ai-parse-button" :disabled="props.aiBreakdownBusy || props.aiOptimizeBusy || busy || loadingContext" @click="emit('aiBreakdown')"><ListTree :size="15" />{{ props.aiBreakdownBusy ? '拆解中...' : 'AI 拆解' }}</button>
      <button type="button" class="quick-ai-parse-button secondary" :disabled="props.aiBreakdownBusy || props.aiOptimizeBusy || busy || loadingContext" @click="emit('aiOptimize')"><Sparkles :size="15" />{{ props.aiOptimizeBusy ? '优化中...' : '优化说明' }}</button>
      <span v-if="props.aiError" class="error">{{ props.aiError }}</span>
      <span v-else-if="props.aiNotice" class="success">{{ props.aiNotice }}</span>
    </div>

    <div class="quick-editor-time-grid">
      <label class="quick-editor-field"><span>开始时间 <small>可选</small></span><input v-model="form.startTime" type="datetime-local" /></label>
      <label class="quick-editor-field"><span>截止时间 <small>可选</small></span><input v-model="form.deadlineTime" type="datetime-local" /></label>
    </div>
    <DesktopQuickReminderPicker v-model="form.remindAt" :base-time="reminderBaseTime" :base-label="reminderBaseLabel" :presets="props.reminderPresets" :timezone="props.timezone" :disabled="busy || loadingContext" @error="emit('error', $event)" />

    <fieldset class="quick-editor-field quick-assignee-picker">
      <legend><UsersRound :size="15" />执行人</legend>
      <div v-if="loadingContext" class="quick-inline-loading">正在加载团队成员...</div>
      <div v-else class="quick-member-options">
        <label v-for="member in members" :key="member.userId"><input v-model="form.assigneeUserIds" type="checkbox" :value="member.userId" /><span>{{ member.nickname }}</span></label>
        <p v-if="form.teamId && !members.length">该团队暂无活跃成员</p>
      </div>
    </fieldset>

    <footer class="quick-editor-actions"><button class="primary" :disabled="busy || props.aiBreakdownBusy || props.aiOptimizeBusy || loadingContext || !teams.length"><Save :size="16" />{{ busy ? '创建中...' : '创建团队任务' }}</button></footer>
  </form>
</template>
