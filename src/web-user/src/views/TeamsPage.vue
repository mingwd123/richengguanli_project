<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
const showJoinPanel = ref(false)

function goDetail(id: number) {
  router.push(`/teams/${id}`)
}

function handleCreate() {
  store.createTeam()
}

function handleJoin() {
  store.joinTeam()
  showJoinPanel.value = false
}
</script>

<template>
  <section class="split-layout">
    <section class="list-card">
      <div class="section-head">
        <h2>我的团队</h2>
        <div class="top-actions">
          <button @click="showJoinPanel = !showJoinPanel">加入团队</button>
          <button class="primary" @click="handleCreate">创建团队</button>
        </div>
      </div>

      <!-- 创建团队表单 -->
      <form class="inline-form" @submit.prevent="handleCreate">
        <input v-model="store.teamForm.name" placeholder="团队名称" />
        <button class="primary">创建</button>
      </form>

      <!-- 加入团队入口（折叠面板） -->
      <div v-if="showJoinPanel" style="margin-top:12px;padding:14px;background:#f8fafc;border:1px solid #e5eaf2;border-radius:7px">
        <h3 style="margin-top:0">加入团队</h3>
        <form class="inline-form" @submit.prevent="handleJoin">
          <input v-model="store.joinForm.inviteCode" placeholder="输入邀请码" />
          <button class="primary">加入</button>
        </form>
      </div>

      <!-- 团队列表 -->
      <article v-for="team in store.teams" :key="team.id" class="team-line" style="cursor:pointer" @click="goDetail(team.id)">
        <div>
          <strong>{{ team.name }}</strong>
          <small>邀请码：{{ team.inviteCode }}</small>
        </div>
        <span>{{ team.memberCount }} 成员</span>
        <span>{{ team.activeTaskCount }} 任务</span>
      </article>
      <p v-if="!store.teams.length" class="hint" style="text-align:center;padding:40px 0">暂无团队，创建一个或加入已有团队</p>
    </section>

    <aside class="detail-panel">
      <h2>邀请码</h2>
      <p class="muted">将邀请码分享给成员即可加入团队</p>
      <article v-for="team in store.teams.slice(0, 1)" :key="team.id" class="code-box">
        {{ team.inviteCode }}
      </article>
    </aside>
  </section>
</template>
