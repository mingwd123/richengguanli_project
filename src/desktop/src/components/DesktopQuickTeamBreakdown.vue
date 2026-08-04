<script lang="ts">
export interface QuickTeamBreakdownTask {
  title: string
  description: string
  deadlineTime: string
  assigneeUserIds: number[]
  selected: boolean
  error?: string
}
</script>

<script setup lang="ts">
import { computed } from 'vue'
import { CalendarClock, CircleAlert, ListChecks, LoaderCircle, Sparkles, UsersRound, X } from 'lucide-vue-next'
import type { TeamMember } from '@web/types'

const props = withDefaults(defineProps<{
  modelValue?: QuickTeamBreakdownTask[]
  members: TeamMember[]
  busy: boolean
}>(), {
  modelValue: undefined,
})

const emit = defineEmits<{
  'update:modelValue': [tasks: QuickTeamBreakdownTask[]]
  submit: []
  cancel: []
}>()

const taskList = computed(() => props.modelValue ?? [])
const selectedCount = computed(() => taskList.value.filter(task => task.selected).length)

function updateTask(index: number, patch: Partial<QuickTeamBreakdownTask>) {
  const tasks = taskList.value.map((task, taskIndex) => {
    if (taskIndex !== index) return task
    return {
      ...task,
      ...patch,
      error: Object.prototype.hasOwnProperty.call(patch, 'error') ? patch.error : '',
    }
  })
  emit('update:modelValue', tasks)
}

function updateText(index: number, field: 'title' | 'description' | 'deadlineTime', event: Event) {
  updateTask(index, { [field]: (event.target as HTMLInputElement | HTMLTextAreaElement).value })
}

function toggleAssignee(index: number, userId: number, checked: boolean) {
  const current = taskList.value[index]?.assigneeUserIds || []
  const assigneeUserIds = checked
    ? [...new Set([...current, userId])]
    : current.filter(id => id !== userId)
  updateTask(index, { assigneeUserIds })
}

function submitSelected() {
  if (!props.busy && selectedCount.value > 0) emit('submit')
}
</script>

<template>
  <form class="quick-team-breakdown" @submit.prevent="submitSelected">
    <header class="quick-breakdown-heading">
      <span class="quick-breakdown-icon"><Sparkles :size="17" /></span>
      <div>
        <strong>AI 子任务草稿</strong>
        <small>选择并检查需要创建的任务</small>
      </div>
    </header>

    <section class="quick-breakdown-list" aria-label="AI 子任务草稿列表">
      <article
        v-for="(task, index) in taskList"
        :key="index"
        :class="['quick-breakdown-item', { selected: task.selected, invalid: task.error }]"
      >
        <label class="quick-breakdown-select">
          <input
            type="checkbox"
            :checked="task.selected"
            :disabled="busy"
            @change="updateTask(index, { selected: ($event.target as HTMLInputElement).checked })"
          />
          <span>子任务 {{ index + 1 }}</span>
          <em>{{ task.selected ? '将创建' : '已忽略' }}</em>
        </label>

        <label class="quick-editor-field">
          <span>标题</span>
          <input
            :value="task.title"
            maxlength="200"
            placeholder="子任务标题"
            :disabled="busy || !task.selected"
            @input="updateText(index, 'title', $event)"
          />
        </label>

        <label class="quick-editor-field">
          <span>说明 <small>可选</small></span>
          <textarea
            :value="task.description"
            rows="2"
            placeholder="交付内容或注意事项"
            :disabled="busy || !task.selected"
            @input="updateText(index, 'description', $event)"
          ></textarea>
        </label>

        <label class="quick-editor-field quick-breakdown-deadline">
          <span><CalendarClock :size="14" />截止时间 <small>可选</small></span>
          <input
            :value="task.deadlineTime"
            type="datetime-local"
            :disabled="busy || !task.selected"
            @input="updateText(index, 'deadlineTime', $event)"
          />
        </label>

        <fieldset class="quick-editor-field quick-breakdown-assignees" :disabled="busy || !task.selected">
          <legend><UsersRound :size="14" />执行人</legend>
          <div class="quick-member-options">
            <label v-for="member in members" :key="member.userId">
              <input
                type="checkbox"
                :checked="task.assigneeUserIds.includes(member.userId)"
                :disabled="busy || !task.selected"
                @change="toggleAssignee(index, member.userId, ($event.target as HTMLInputElement).checked)"
              />
              <span>{{ member.nickname }}</span>
            </label>
            <p v-if="!members.length">暂无可选成员</p>
          </div>
        </fieldset>

        <p v-if="task.error" class="quick-breakdown-error" role="alert">
          <CircleAlert :size="14" />{{ task.error }}
        </p>
      </article>

      <div v-if="!taskList.length" class="quick-breakdown-empty">
        <ListChecks :size="20" />
        <span>AI 未生成可用的子任务</span>
      </div>
    </section>

    <footer class="quick-editor-actions quick-breakdown-actions">
      <span>已选择 <strong>{{ selectedCount }}</strong> 项</span>
      <div>
        <button type="button" :disabled="busy" @click="emit('cancel')"><X :size="15" />取消</button>
        <button class="primary" :disabled="busy || selectedCount === 0">
          <LoaderCircle v-if="busy" class="spinning" :size="16" />
          <ListChecks v-else :size="16" />
          {{ busy ? '创建中...' : `创建所选 (${selectedCount})` }}
        </button>
      </div>
    </footer>
  </form>
</template>

<style scoped>
.quick-team-breakdown {
  display: grid;
  gap: 14px;
  padding: 17px 2px 28px;
}

.quick-breakdown-heading {
  min-width: 0;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  padding: 0 2px 12px;
  border-bottom: 1px solid var(--border-soft);
}

.quick-breakdown-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 1px solid var(--primary);
  border-radius: 7px;
  background: var(--primary-soft);
  color: var(--primary);
}

.quick-breakdown-heading div {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.quick-breakdown-heading strong {
  color: var(--text-strong);
  font-size: 13px;
}

.quick-breakdown-heading small {
  color: var(--muted);
  font-size: 10px;
}

.quick-breakdown-list {
  display: grid;
  gap: 10px;
}

.quick-breakdown-item {
  min-width: 0;
  display: grid;
  gap: 11px;
  padding: 11px;
  border: 1px solid var(--border-soft);
  border-radius: 7px;
  background: var(--surface);
}

.quick-breakdown-item.selected {
  border-color: color-mix(in srgb, var(--primary) 55%, var(--border-soft));
}

.quick-breakdown-item.invalid {
  border-color: var(--danger);
}

.quick-breakdown-item:not(.selected) > :not(.quick-breakdown-select, .quick-breakdown-error) {
  opacity: .58;
}

.quick-breakdown-select {
  min-width: 0;
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr) auto;
  align-items: center;
  gap: 7px;
  color: var(--text-strong);
  font-size: 11px;
  font-weight: 800;
}

.quick-breakdown-select input {
  width: 15px;
  height: 15px;
  margin: 0;
  padding: 0;
  accent-color: var(--primary);
}

.quick-breakdown-select span {
  min-width: 0;
}

.quick-breakdown-select em {
  color: var(--muted);
  font-size: 9px;
  font-style: normal;
  font-weight: 600;
}

.quick-breakdown-item.selected .quick-breakdown-select em {
  color: var(--primary);
}

.quick-breakdown-item textarea {
  min-height: 62px;
}

.quick-breakdown-deadline > span,
.quick-breakdown-assignees > legend {
  display: flex;
  align-items: center;
  gap: 5px;
}

.quick-breakdown-deadline svg,
.quick-breakdown-assignees svg {
  color: var(--primary);
}

.quick-breakdown-assignees {
  min-width: 0;
}

.quick-breakdown-error {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  padding: 8px 9px;
  border-radius: 6px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 10px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.quick-breakdown-error svg {
  flex: 0 0 auto;
  margin-top: 1px;
}

.quick-breakdown-empty {
  min-height: 120px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  border: 1px dashed var(--border-strong);
  border-radius: 7px;
  color: var(--muted);
  font-size: 10px;
}

.quick-breakdown-actions {
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.quick-breakdown-actions > span {
  flex: 0 0 auto;
  color: var(--muted);
  font-size: 10px;
}

.quick-breakdown-actions > span strong {
  color: var(--primary);
  font-size: 12px;
}

.quick-breakdown-actions > div {
  min-width: 0;
  display: flex;
  justify-content: flex-end;
  gap: 7px;
}

.quick-breakdown-actions button {
  min-width: 0;
  padding-inline: 10px;
  white-space: nowrap;
}

@media (max-width: 420px) {
  .quick-breakdown-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .quick-breakdown-actions > div {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
  }
}
</style>
