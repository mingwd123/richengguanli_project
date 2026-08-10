<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'
import { formatTime, statusLabel } from '../utils/helpers'
import type { Team, TeamMember, MyTask } from '../types'

const props = defineProps<{ id: string }>()
const router = useRouter()
const store = useAppStore()

const members = ref<TeamMember[]>([])
const tasks = ref<MyTask[]>([])
const loading = ref(false)
const newInviteCode = ref('')

const team = computed<Team | undefined>(() => store.teams.find(t => String(t.id) === props.id))
const isOwnerOrAdmin = computed(() => team.value?.myRole === 'owner' || team.value?.myRole === 'admin')

async function loadMembers() {
  loading.value = true
  try {
    const [memberData, taskData] = await Promise.all([
      store.request<{ list: TeamMember[] }>(`/teams/${props.id}/members`),
      store.request<{ list: MyTask[] }>(`/teams/${props.id}/tasks?size=100`),
    ])
    members.value = memberData.list || []
    tasks.value = taskData.list || []
  } catch (e: any) {
    store.notify(e.message || '加载成员列表失败')
  } finally {
    loading.value = false
  }
}

function handleSetRole(member: TeamMember, role: string) {
  store.setMemberRole(Number(props.id), member.userId, role)
}

function handleRemoveMember(member: TeamMember) {
  if (!confirm(`确认移除成员 ${member.nickname}？`)) return
  store.removeMember(Number(props.id), member.userId)
}

async function handleRegenerateCode() {
  const code = await store.regenerateInviteCode(Number(props.id))
  if (code) newInviteCode.value = code
}

function goBack() {
  router.push('/teams')
}

function goTask(id: number) { router.push(`/tasks/${id}`) }

onMounted(loadMembers)
</script>

<template>
  <section class="list-page">
    <div class="page-topbar" style="padding:0">
      <div>
        <button @click="goBack" style="border:0;background:transparent;padding:0;color:#2f80ed;font-weight:700">← 返回团队列表</button>
      </div>
    </div>

    <div v-if="!team" class="hint" style="text-align:center;padding:60px 0">团队未找到</div>

    <template v-else>
      <!-- 团队信息 -->
      <section class="settings-card" style="margin-top:16px">
        <div class="section-head">
          <h2>{{ team.name }}</h2>
          <span :class="['tag', team.myRole === 'owner' ? 'danger' : team.myRole === 'admin' ? 'warning' : 'blue']">{{ statusLabel(team.myRole) }}</span>
        </div>

        <div style="display:grid;gap:12px;margin-top:16px">
          <div>
            <span class="muted">邀请码：</span>
            <code style="font-size:18px;font-weight:800;letter-spacing:2px">{{ team.inviteCode }}</code>
            <button v-if="isOwnerOrAdmin" style="margin-left:10px" @click="handleRegenerateCode">重新生成</button>
          </div>
          <div v-if="newInviteCode">
            <span class="muted">新邀请码：</span>
            <code style="font-size:18px;font-weight:800;letter-spacing:2px;color:#2f80ed">{{ newInviteCode }}</code>
          </div>
          <div>
            <span class="muted">成员数：</span>
            <span>{{ team.memberCount }}</span>
          </div>
          <div>
            <span class="muted">活跃任务：</span>
            <span>{{ team.activeTaskCount }}</span>
          </div>
        </div>
      </section>

      <section class="table-card" style="margin-top:16px">
        <div class="section-head"><h2>团队任务</h2><button @click="router.push('/tasks')">任务工作台</button></div>
        <article v-for="task in tasks" :key="task.id" class="table-row" style="grid-template-columns:minmax(0,1fr) auto auto" @click="goTask(task.id)">
          <div><strong>{{ task.title }}</strong><small>{{ task.groupName || '未分组' }} · {{ formatTime(task.deadlineTime || task.startTime) }}</small></div>
          <span :class="['tag', task.status === 'completed' ? 'blue' : ['active', 'pending_approval', 'unassigned'].includes(task.status) ? 'warning' : 'danger']">{{ statusLabel(task.status) }}</span>
          <button @click.stop="goTask(task.id)">查看</button>
        </article>
        <p v-if="!tasks.length" class="hint" style="text-align:center;padding:24px 0">该团队暂无任务</p>
      </section>

      <!-- 成员列表 -->
      <section class="table-card" style="margin-top:16px">
        <div class="section-head">
          <h2>成员列表</h2>
        </div>

        <div v-if="loading" class="hint" style="text-align:center;padding:30px 0">加载中...</div>

        <template v-else>
          <article v-for="m in members" :key="m.id" class="table-row" style="grid-template-columns:minmax(0,1fr) auto auto auto auto">
            <div>
              <strong>{{ m.nickname }}</strong>
              <small>{{ m.phone }}</small>
            </div>
            <span :class="['tag', m.role === 'owner' ? 'danger' : m.role === 'admin' ? 'warning' : 'blue']">{{ statusLabel(m.role) }}</span>
            <span :class="['tag', m.status === 'active' ? 'blue' : 'danger']">{{ statusLabel(m.status) }}</span>
            <small>{{ formatTime(m.joinedAt) }}</small>
            <div class="top-actions" v-if="isOwnerOrAdmin && m.role !== 'owner'">
              <select
                :value="m.role"
                style="width:auto;height:30px;font-size:12px"
                @change="handleSetRole(m, ($event.target as HTMLSelectElement).value)"
              >
                <option value="member">成员</option>
                <option value="admin">管理员</option>
              </select>
              <button @click="handleRemoveMember(m)">移除</button>
            </div>
          </article>
          <p v-if="!members.length" class="hint" style="text-align:center;padding:30px 0">暂无成员</p>
        </template>
      </section>
    </template>
  </section>
</template>
