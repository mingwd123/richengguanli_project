<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import type { TeamMember, TeamTask, TeamTaskAssignee } from '../types'

const props = defineProps<{ id: string; userId: string }>()
const router = useRouter()
const store = useAppStore()
const task = ref<TeamTask | null>(null)
const members = ref<TeamMember[]>([])
const selectedUserId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)

const originalAssignee = computed<TeamTaskAssignee | undefined>(() =>
  task.value?.assignees.find(item => String(item.userId) === props.userId)
)
const selectableMembers = computed(() => members.value.filter(member => member.status === 'active' && member.userId !== Number(props.userId)))

async function loadData() {
  loading.value = true
  try {
    task.value = await store.request<TeamTask>(`/team-tasks/${props.id}`)
    const data = await store.request<{ list: TeamMember[] }>(`/teams/${task.value.teamId}/members`)
    members.value = data.list || []
  } catch (e: any) {
    store.notify(e.message || '加载团队成员失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!originalAssignee.value || !selectedUserId.value) return
  saving.value = true
  try {
    await store.request(`/team-tasks/${props.id}/reassign`, {
      method: 'POST',
      body: JSON.stringify({
        originalAssigneeUserId: originalAssignee.value.userId,
        newAssigneeUserId: selectedUserId.value
      })
    })
    store.notify('任务已重新分配')
    router.push(`/tasks/${props.id}`)
  } catch (e: any) {
    store.notify(e.message || '重新分配失败')
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push(`/tasks/${props.id}`)
}

onMounted(loadData)
</script>

<template>
  <section class="list-page">
    <button class="back-link" @click="goBack">← 返回任务详情</button>

    <div v-if="loading" class="hint page-state">加载中...</div>
    <div v-else-if="!task || !originalAssignee" class="hint page-state">未找到需重新分配的执行人</div>

    <section v-else class="form-card reassign-editor">
      <h2>重新分配任务</h2>
      <p class="muted">原执行人 <strong>{{ originalAssignee.nickname }}</strong> 已拒绝任务，请从团队成员中选择新的执行人。</p>

      <div class="member-options">
        <label v-for="member in selectableMembers" :key="member.userId" :class="['member-option', { selected: selectedUserId === member.userId }]">
          <input v-model="selectedUserId" type="radio" name="new-assignee" :value="member.userId" />
          <span class="member-avatar">{{ member.nickname.slice(0, 1) }}</span>
          <span>
            <strong>{{ member.nickname }}</strong>
            <small>{{ member.role === 'owner' ? '团队所有者' : member.role === 'admin' ? '管理员' : '成员' }} · {{ member.phone }}</small>
          </span>
        </label>
        <p v-if="!selectableMembers.length" class="hint">团队内没有可选择的其他活跃成员。</p>
      </div>

      <div class="form-actions">
        <button @click="goBack">取消</button>
        <button class="primary" :disabled="saving || !selectedUserId" @click="submit">
          {{ saving ? '提交中...' : '确认重新分配' }}
        </button>
      </div>
    </section>
  </section>
</template>

<style scoped>
.back-link { border: 0; background: transparent; padding: 0; color: #2f80ed; font-weight: 700; }
.page-state { text-align: center; padding: 60px 0; }
.reassign-editor { max-width: 680px; margin-top: 16px; }
.member-options { display: grid; gap: 10px; margin-top: 20px; }
.member-option { display: flex; align-items: center; gap: 12px; border: 1px solid #e2e8f0; border-radius: 10px; padding: 14px; cursor: pointer; }
.member-option.selected { border-color: #2f80ed; background: #f0f7ff; }
.member-option input { width: auto; }
.member-avatar { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 50%; background: #dbeafe; color: #1d4ed8; font-weight: 800; }
.member-option strong, .member-option small { display: block; }
.member-option small { margin-top: 4px; color: #64748b; }
</style>
