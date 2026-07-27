<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { statusLabel } from '../utils/helpers'
import type { TeamTask, TeamTaskAssignee } from '../types'

const props = defineProps<{ id: string; userId: string }>()
const router = useRouter()
const store = useAppStore()
const task = ref<TeamTask | null>(null)
const loading = ref(false)
const saving = ref(false)
const selectedStatus = ref('')

const assignee = computed<TeamTaskAssignee | undefined>(() =>
  task.value?.assignees.find(item => String(item.userId) === props.userId)
)

const statusOptions = [
  { value: 'pending', label: '待接受', description: '任务已分配，等待执行人确认。' },
  { value: 'accepted', label: '已接受', description: '执行人已确认接收任务。' },
  { value: 'rejected', label: '已拒绝', description: '执行人已拒绝该任务，可重新分配。' },
  { value: 'completed', label: '已完成', description: '执行人已完成任务。' }
]

async function loadDetail() {
  loading.value = true
  try {
    task.value = await store.request<TeamTask>(`/team-tasks/${props.id}`)
    selectedStatus.value = assignee.value?.assignStatus || ''
  } catch (e: any) {
    store.notify(e.message || '加载任务详情失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!assignee.value || !selectedStatus.value) return
  saving.value = true
  try {
    await store.request(`/team-tasks/${props.id}/assignees/${assignee.value.userId}/status`, {
      method: 'PUT',
      body: JSON.stringify({ assignStatus: selectedStatus.value })
    })
    store.notify('执行状态已修正')
    router.push(`/tasks/${props.id}`)
  } catch (e: any) {
    store.notify(e.message || '修正失败')
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push(`/tasks/${props.id}`)
}

onMounted(loadDetail)
</script>

<template>
  <section class="list-page">
    <button class="back-link" @click="goBack">← 返回任务详情</button>

    <div v-if="loading" class="hint page-state">加载中...</div>
    <div v-else-if="!task || !assignee" class="hint page-state">未找到对应执行人</div>

    <section v-else class="form-card status-editor">
      <h2>修正执行状态</h2>
      <p class="muted">为 <strong>{{ assignee.nickname }}</strong> 选择正确的任务执行状态。</p>

      <div class="current-status">
        当前状态：<span class="tag warning">{{ statusLabel(assignee.assignStatus) }}</span>
      </div>

      <div class="status-options">
        <label v-for="option in statusOptions" :key="option.value" :class="['status-option', { selected: selectedStatus === option.value }]">
          <input v-model="selectedStatus" type="radio" name="assign-status" :value="option.value" />
          <span>
            <strong>{{ option.label }}</strong>
            <small>{{ option.description }}</small>
          </span>
        </label>
      </div>

      <div class="form-actions">
        <button @click="goBack">取消</button>
        <button class="primary" :disabled="saving || selectedStatus === assignee.assignStatus" @click="submit">
          {{ saving ? '保存中...' : '确认修正' }}
        </button>
      </div>
    </section>
  </section>
</template>

<style scoped>
.back-link { border: 0; background: transparent; padding: 0; color: #2f80ed; font-weight: 700; }
.page-state { text-align: center; padding: 60px 0; }
.status-editor { max-width: 680px; margin-top: 16px; }
.current-status { margin: 20px 0 12px; }
.status-options { display: grid; gap: 10px; }
.status-option { display: flex; gap: 12px; align-items: flex-start; border: 1px solid #e2e8f0; border-radius: 10px; padding: 14px; cursor: pointer; }
.status-option.selected { border-color: #2f80ed; background: #f0f7ff; }
.status-option input { width: auto; margin-top: 4px; }
.status-option strong, .status-option small { display: block; }
.status-option small { margin-top: 4px; color: #64748b; }
</style>
