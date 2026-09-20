<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { useRouter } from 'vue-router'
import { useTicketsStore } from '../stores/tickets'
import { useAppStore } from '../stores/app'
import {
  ticketCategoryLabel, ticketCloseReasonLabel, ticketModuleLabel, ticketStatusLabel,
} from '../utils/ticketLabels'
import type { TicketListItem } from '../utils/ticketLabels'

const router = useRouter()
const tickets = useTicketsStore()
const appStore = useAppStore()

const keyword = ref('')
const status = ref('')
const category = ref('')
const module = ref('')
const sort = ref('activity')
const view = ref<'all' | 'mine' | 'following'>('all')
const page = ref(1)
let searchTimer: ReturnType<typeof setTimeout> | undefined
let searchVersion = 0

const createOpen = ref(false)
const createForm = ref({
  title: '', category: 'bug', module: 'schedule', description: '',
  steps: '', expectedResult: '', pagePath: '',
})
const createAttachments = ref<{ id: number; name: string }[]>([])
const createError = ref('')
const uploading = ref(false)
const similar = ref<TicketListItem[]>([])
const similarSearching = ref(false)

const totalPages = computed(() => {
  const result = tickets.listResult
  return Math.max(1, Math.ceil((result?.total ?? 0) / (result?.size ?? 20)))
})
const createDirty = computed(() =>
  createForm.value.title.trim() !== '' || createForm.value.description.trim() !== '')

async function load(pageNumber = 1) {
  page.value = pageNumber
  await tickets.list({
    view: view.value, keyword: keyword.value.trim(), status: status.value,
    category: category.value, module: module.value, sort: sort.value,
    page: pageNumber, size: 20,
  })
}

function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => load(1), 300)
}

function openDetail(item: TicketListItem) {
  router.push(`/tickets/${item.id}`)
}

function openCreate() {
  if (!tickets.requireEnabled()) return
  createOpen.value = true
  createError.value = ''
  createForm.value.pagePath = router.currentRoute.value.path
}

function closeCreate() {
  if (createDirty.value && !window.confirm('工单还没有提交，确定放弃已填写的内容吗？')) return
  createOpen.value = false
}

/** 提交前查重（§2.2）：防抖查询相似工单，仅提示不阻止。 */
async function searchSimilar() {
  const title = createForm.value.title.trim()
  if (title.length < 2) { similar.value = []; return }
  similarSearching.value = true
  const result = await tickets.list({ keyword: title, size: 5, page: 1 })
  similarSearching.value = false
  similar.value = (result?.items ?? []).slice(0, 5)
}

watch(() => createForm.value.title, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { void searchSimilar() }, 400)
})

async function onPickFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  for (const file of files) {
    if (createAttachments.value.length >= 5) { appStore.notify('最多 5 张截图'); break }
    if (file.size > 10 * 1024 * 1024) { appStore.notify(`「${file.name}」超过 10MB`); continue }
    uploading.value = true
    const uploaded = await tickets.uploadAttachment(file)
    uploading.value = false
    if (uploaded) createAttachments.value.push({ id: uploaded.id, name: file.name })
  }
}

function removeAttachment(id: number) {
  createAttachments.value = createAttachments.value.filter(item => item.id !== id)
  void tickets.removeAttachment(id)
}

async function submitCreate() {
  createError.value = ''
  const form = createForm.value
  if (form.title.trim().length < 5) { createError.value = '标题至少 5 个字'; return }
  if (form.description.trim().length < 10) { createError.value = '问题描述至少 10 个字'; return }
  try {
    const created = await tickets.create({
      title: form.title.trim(),
      category: form.category,
      module: form.module,
      description: form.description.trim(),
      steps: form.steps.trim() || null,
      expectedResult: form.expectedResult.trim() || null,
      pagePath: form.pagePath.trim() || null,
      attachmentIds: createAttachments.value.map(item => item.id),
      idempotencyKey: `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`,
    })
    createOpen.value = false
    createForm.value = { title: '', category: 'bug', module: 'schedule', description: '', steps: '', expectedResult: '', pagePath: '' }
    createAttachments.value = []
    similar.value = []
    appStore.notify('工单已提交，进展会通过通知更新')
    if (created) router.push(`/tickets/${created.id}`)
  } catch (error: any) {
    createError.value = error.message || '提交失败，请稍后重试'
  }
}

// 表单未提交时离开需确认（§12.1）。
onBeforeRouteLeave(() => {
  if (createOpen.value && createDirty.value) {
    return window.confirm('工单还没有提交，确定离开吗？')
  }
  return true
})

watch([status, category, module, sort, view], () => load(1))
onMounted(async () => {
  await tickets.loadSettings(true)
  if (tickets.enabled) await load(1)
})
</script>

<template>
  <section class="list-page">
    <section v-if="!tickets.settingsLoaded" class="list-card">
      <div class="hint" style="padding:36px;text-align:center">正在加载问题反馈模块…</div>
    </section>

    <section v-else-if="!tickets.enabled" class="list-card">
      <div class="hint" style="padding:36px;text-align:center">
        问题反馈模块当前未开放。
        <button style="margin-left:12px" @click="tickets.loadSettings(true); load(1)">重试</button>
      </div>
    </section>

    <template v-else>
      <section class="list-card">
        <div class="section-head">
          <div><h2>问题反馈</h2><p class="muted">公开的问题大厅：查看已有问题、跟进处理进展，减少重复提交</p></div>
          <button class="primary" @click="openCreate">提交工单</button>
        </div>
        <div class="search-bar">
          <input
            v-model="keyword"
            class="ticket-search"
            type="search"
            placeholder="搜索标题或工单编号"
            aria-label="搜索工单"
            @input="onSearchInput"
          />
          <select v-model="status" aria-label="状态筛选">
            <option value="">全部状态</option>
            <option value="open">待处理</option><option value="in_progress">处理中</option>
            <option value="waiting_reporter">待补充</option><option value="resolved">已解决</option>
            <option value="closed">已关闭</option>
          </select>
          <select v-model="category" aria-label="问题类型">
            <option value="">全部类型</option>
            <option value="bug">功能异常</option><option value="display">显示问题</option>
            <option value="data">数据问题</option><option value="account">账号问题</option>
            <option value="question">使用咨询</option><option value="suggestion">功能建议</option>
            <option value="other">其他</option>
          </select>
          <select v-model="module" aria-label="所属模块">
            <option value="">全部模块</option>
            <option value="schedule">日程</option><option value="team_task">团队任务</option>
            <option value="daily_progress">每日进度</option><option value="fatigue">疲劳评估</option>
            <option value="notification">通知提醒</option><option value="ai">AI</option>
            <option value="account">账号与设置</option><option value="desktop">桌面端</option>
            <option value="other">其他</option>
          </select>
          <select v-model="sort" aria-label="排序">
            <option value="activity">最近活动</option>
            <option value="newest">最新提交</option>
            <option value="reacted">最多遇到</option>
          </select>
        </div>
        <div class="ticket-views" role="tablist">
          <button :class="{ active: view === 'all' }" role="tab" @click="view = 'all'">全部</button>
          <button :class="{ active: view === 'mine' }" role="tab" @click="view = 'mine'">我的提交</button>
          <button :class="{ active: view === 'following' }" role="tab" @click="view = 'following'">我的关注</button>
        </div>

        <div v-if="tickets.listLoading" class="hint" style="padding:36px;text-align:center">加载中...</div>
        <div v-else-if="tickets.listError" class="hint" style="padding:36px;text-align:center">
          {{ tickets.listError }}
          <button style="margin-left:12px" @click="load(page)">重试</button>
        </div>
        <p v-else-if="!tickets.listResult?.items.length" class="hint" style="padding:36px;text-align:center">
          还没有符合条件的工单，点「提交工单」反馈第一个问题。
        </p>
        <template v-else>
          <article
            v-for="item in tickets.listResult.items"
            :key="item.id"
            class="table-row ticket-row"
            @click="openDetail(item)"
          >
            <div class="ticket-row-main">
              <span v-if="item.pinned" class="tag">置顶</span>
              <span :class="['tag', `ticket-status-${item.status}`]">{{ ticketStatusLabel(item.status) }}</span>
              <strong>{{ item.title }}</strong>
              <small class="muted">
                {{ item.ticketNo }} · {{ ticketCategoryLabel(item.category) }} · {{ ticketModuleLabel(item.module) }}
                · {{ item.authorName }}
                <template v-if="item.closeReason"> · {{ ticketCloseReasonLabel(item.closeReason) }}</template>
              </small>
            </div>
            <div class="ticket-row-meta">
              <span>💬 {{ item.replyCount }}</span>
              <span>🙋 {{ item.reactionCount }}</span>
              <small class="muted">{{ item.lastActivityAt?.slice(0, 10) }}</small>
            </div>
          </article>
          <div class="ticket-pagination">
            <button :disabled="page <= 1" @click="load(page - 1)">上一页</button>
            <span class="muted">第 {{ tickets.listResult?.page }} / {{ totalPages }} 页 · 共 {{ tickets.listResult?.total }} 条</span>
            <button :disabled="page >= totalPages" @click="load(page + 1)">下一页</button>
          </div>
        </template>
      </section>
    </template>

    <div v-if="createOpen" class="modal-backdrop" @click.self="closeCreate">
      <section class="modal-panel ticket-create-panel">
        <div class="modal-head">
          <h2>提交工单</h2>
          <button class="modal-close" aria-label="关闭" @click="closeCreate">✕</button>
        </div>
        <p class="muted" style="margin-bottom:12px">工单内容和截图将对所有测试用户公开。</p>

        <div v-if="similar.length" class="ticket-similar">
          <p class="muted">发现相似的工单，可以先查看或标记「我也遇到」：</p>
          <div v-for="item in similar" :key="item.id" class="ticket-similar-row">
            <span :class="['tag', `ticket-status-${item.status}`]">{{ ticketStatusLabel(item.status) }}</span>
            <span class="ticket-similar-title">{{ item.title }}</span>
            <button type="button" class="plain-button" @click="createOpen = false; router.push(`/tickets/${item.id}`)">查看</button>
          </div>
        </div>

        <form class="ticket-form" @submit.prevent="submitCreate">
          <label>
            标题（5～100 字）
            <input v-model="createForm.title" maxlength="120" placeholder="一句话概括问题" />
          </label>
          <div class="ticket-form-grid">
            <label>
              问题类型
              <select v-model="createForm.category">
                <option value="bug">功能异常</option><option value="display">显示问题</option>
                <option value="data">数据问题</option><option value="account">账号问题</option>
                <option value="question">使用咨询</option><option value="suggestion">功能建议</option>
                <option value="other">其他</option>
              </select>
            </label>
            <label>
              所属模块
              <select v-model="createForm.module">
                <option value="schedule">日程</option><option value="team_task">团队任务</option>
                <option value="daily_progress">每日进度</option><option value="fatigue">疲劳评估</option>
                <option value="notification">通知提醒</option><option value="ai">AI</option>
                <option value="account">账号与设置</option><option value="desktop">桌面端</option>
                <option value="other">其他</option>
              </select>
            </label>
          </div>
          <label>
            问题描述（10～5000 字）
            <textarea v-model="createForm.description" rows="5" placeholder="发生了什么？在什么操作后出现？"></textarea>
          </label>
          <label>
            复现步骤（可选）
            <textarea v-model="createForm.steps" rows="3" placeholder="1. … 2. …"></textarea>
          </label>
          <label>
            预期结果（可选）
            <textarea v-model="createForm.expectedResult" rows="2"></textarea>
          </label>
          <label>
            发生页面（可选，站内路径）
            <input v-model="createForm.pagePath" placeholder="/schedules" />
          </label>
          <label>
            截图（可选，最多 5 张，每张 ≤10MB，公开可见）
            <input type="file" accept="image/jpeg,image/png,image/webp" multiple @change="onPickFiles" />
          </label>
          <div v-if="createAttachments.length" class="ticket-attachments">
            <span v-for="item in createAttachments" :key="item.id" class="tag">
              {{ item.name }}
              <button type="button" aria-label="移除截图" @click="removeAttachment(item.id)">✕</button>
            </span>
            <small v-if="uploading" class="muted">上传中…</small>
          </div>
          <p v-if="createError" class="field-error">{{ createError }}</p>
          <div class="form-actions">
            <button type="button" @click="closeCreate">取消</button>
            <button type="submit" class="primary" :disabled="tickets.submitting || uploading">
              {{ tickets.submitting ? '提交中...' : '提交工单' }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>

<style scoped>
.ticket-search { flex: 1 1 220px; }
.ticket-views { display: flex; gap: 6px; margin: 10px 0 4px; flex-wrap: wrap; }
.ticket-views button { border-radius: 999px; padding: 3px 14px; }
.ticket-views button.active { border-color: var(--primary); color: var(--primary); font-weight: 700; }
.ticket-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; cursor: pointer; flex-wrap: wrap; }
.ticket-row-main { display: grid; gap: 4px; min-width: 0; }
.ticket-row-main strong { overflow-wrap: anywhere; }
.ticket-row-meta { display: flex; align-items: center; gap: 10px; color: var(--muted); font-size: 12px; }
.ticket-pagination { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; gap: 10px; }
.ticket-create-panel { width: min(720px, 100%); }
.ticket-form { display: grid; gap: 14px; }
.ticket-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.ticket-similar { border: 1px solid var(--border-soft, #e5e7eb); border-radius: 8px; padding: 10px 12px; margin-bottom: 12px; display: grid; gap: 6px; }
.ticket-similar-row { display: flex; align-items: center; gap: 8px; }
.ticket-similar-title { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.ticket-attachments { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.ticket-attachments .tag button { margin-left: 4px; border: 0; background: transparent; cursor: pointer; }
@media (max-width: 700px) {
  .ticket-form-grid { grid-template-columns: 1fr; }
}
</style>
