<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useTicketsStore } from '../stores/tickets'
import { useAppStore } from '../stores/app'
import {
  ticketCategoryLabel, ticketCloseReasonLabel, ticketModuleLabel, ticketStatusLabel,
} from '../utils/ticketLabels'
import type { TicketAttachmentView } from '../utils/ticketLabels'

const props = defineProps<{ id: string }>()
const router = useRouter()
const tickets = useTicketsStore()
const appStore = useAppStore()

const sequence = { current: 0 }
const messagePage = ref(1)
const messageTotal = ref(0)
const replyContent = ref('')
const replyAttachments = ref<{ id: number; name: string }[]>([])
const uploading = ref(false)
const replyError = ref('')
let replyKey = ''

const reopenOpen = ref(false)
const reopenReason = ref('')
const withdrawOpen = ref(false)
const withdrawNote = ref('')
const actionError = ref('')

const lightboxSrc = ref('')
let lightboxScrollY = 0

const detail = computed(() => tickets.detail)
const isClosed = computed(() => detail.value?.status === 'closed')
const canReply = computed(() => !isClosed.value)
const mergedInfo = computed(() => detail.value?.duplicateOf ?? null)
const totalPages = computed(() => Math.max(1, Math.ceil(messageTotal.value / 20)))

const statusFlow = computed(() => tickets.events.filter(event => event.action !== 'internal_note'))

async function loadAll() {
  const id = Number(props.id)
  if (!Number.isFinite(id)) { tickets.detailError = '无效的工单编号'; return }
  messagePage.value = 1
  const data = await tickets.loadDetail(id, sequence)
  if (!data) return
  const messages = await tickets.loadMessages(id, 1, 20)
  messageTotal.value = messages.total
  await tickets.loadEvents(id)
  await preloadImages(data.attachments)
  for (const message of tickets.messages) await preloadImages(message.attachments)
}

async function loadMoreMessages() {
  const id = Number(props.id)
  const next = messagePage.value + 1
  const data = await tickets.loadMessages(id, next, 20)
  messagePage.value = next
  messageTotal.value = data.total
  for (const message of tickets.messages.slice(-data.items.length)) {
    await preloadImages(message.attachments)
  }
}

async function preloadImages(attachments: TicketAttachmentView[]) {
  for (const attachment of attachments) {
    try { await tickets.attachmentUrl(attachment.id) } catch { /* 加载失败时显示占位 */ }
  }
}

async function openLightbox(attachment: TicketAttachmentView) {
  lightboxScrollY = window.scrollY
  try {
    lightboxSrc.value = await tickets.attachmentUrl(attachment.id)
  } catch {
    appStore.notify('截图加载失败')
  }
}

function closeLightbox() {
  lightboxSrc.value = ''
  void nextTick(() => window.scrollTo(0, lightboxScrollY))
}

async function onPickFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  for (const file of files) {
    if (replyAttachments.value.length >= 5) { appStore.notify('每条回复最多 5 张截图'); break }
    if (file.size > 10 * 1024 * 1024) { appStore.notify(`「${file.name}」超过 10MB`); continue }
    uploading.value = true
    const uploaded = await tickets.uploadAttachment(file)
    uploading.value = false
    if (uploaded) replyAttachments.value.push({ id: uploaded.id, name: file.name })
  }
}

function removeReplyAttachment(id: number) {
  replyAttachments.value = replyAttachments.value.filter(item => item.id !== id)
  void tickets.removeAttachment(id)
}

async function submitReply() {
  replyError.value = ''
  if (!replyContent.value.trim() && !replyAttachments.value.length) {
    replyError.value = '请填写回复内容或添加截图'
    return
  }
  if (!replyKey) replyKey = `reply-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  try {
    await tickets.reply(Number(props.id), {
      content: replyContent.value,
      attachmentIds: replyAttachments.value.map(item => item.id),
      idempotencyKey: replyKey,
    })
    replyContent.value = ''
    replyAttachments.value = []
    replyKey = ''
    await tickets.loadDetail(Number(props.id), sequence)
    const data = await tickets.loadMessages(Number(props.id), 1, 20)
    messagePage.value = 1
    messageTotal.value = data.total
    for (const message of tickets.messages) await preloadImages(message.attachments)
    appStore.notify('回复已发布')
  } catch (error: any) {
    replyError.value = error.message || '回复失败，内容已保留可重试'
  }
}

async function withAction(operation: () => Promise<unknown>, successMessage: string) {
  actionError.value = ''
  try {
    await operation()
    await tickets.loadDetail(Number(props.id), sequence)
    appStore.notify(successMessage)
  } catch (error: any) {
    actionError.value = error.message || '操作失败，请重试'
  }
}

async function toggleFollow() {
  await withAction(
    () => tickets.toggleFollow(Number(props.id), !detail.value?.viewer?.following),
    detail.value?.viewer?.following ? '已取消关注' : '已关注',
  )
}

async function toggleSameIssue() {
  await withAction(
    () => tickets.toggleSameIssue(Number(props.id), !detail.value?.viewer?.reacted),
    detail.value?.viewer?.reacted ? '已取消标记' : '已标记为我也遇到',
  )
}

async function confirmResolved() {
  if (!window.confirm('确认问题已经解决？确认后工单将关闭。')) return
  await withAction(() => tickets.action(`/tickets/${props.id}/confirm`), '已确认解决，工单已关闭')
}

async function submitReopen() {
  if (reopenReason.value.trim().length < 5) { actionError.value = '请填写至少 5 个字的原因'; return }
  await withAction(() => tickets.action(`/tickets/${props.id}/reopen`, { reason: reopenReason.value.trim() }), '已重新进入处理中')
  reopenOpen.value = false
  reopenReason.value = ''
}

async function submitWithdraw() {
  await withAction(() => tickets.action(`/tickets/${props.id}/withdraw`, { note: withdrawNote.value.trim() }), '工单已撤回关闭')
  withdrawOpen.value = false
  withdrawNote.value = ''
}

watch(() => props.id, loadAll)
onMounted(loadAll)
</script>

<template>
  <section class="list-page">
    <div v-if="tickets.detailLoading" class="list-card"><div class="hint" style="padding:36px;text-align:center">加载中...</div></div>

    <section v-else-if="tickets.detailError" class="list-card">
      <div class="hint" style="padding:36px;text-align:center">
        {{ tickets.detailError }}
        <div style="margin-top:12px">
          <button @click="router.push('/tickets')">返回问题反馈</button>
        </div>
      </div>
    </section>

    <template v-else-if="detail">
      <section class="list-card">
        <div class="section-head">
          <div>
            <h2>{{ detail.ticketNo }}</h2>
            <p class="muted">
              {{ ticketCategoryLabel(detail.category) }} · {{ ticketModuleLabel(detail.module) }}
              · 提交人 {{ detail.authorName }} · 提交于 {{ detail.createdAt?.slice(0, 10) }}
            </p>
          </div>
          <div class="ticket-detail-actions">
            <button @click="toggleFollow">{{ detail.viewer?.following ? '取消关注' : '关注' }}</button>
            <button @click="toggleSameIssue">{{ detail.viewer?.reacted ? '取消“我也遇到”' : '我也遇到' }}</button>
          </div>
        </div>
        <div class="ticket-title-line">
          <span v-if="detail.pinned" class="tag">置顶</span>
          <span :class="['tag', `ticket-status-${detail.status}`]">{{ ticketStatusLabel(detail.status) }}</span>
          <strong class="ticket-title">{{ detail.title }}</strong>
        </div>
        <p class="muted ticket-counts">
          💬 {{ detail.replyCount }} 条回复 · 🙋 {{ detail.reactionCount }} 人遇到 · {{ detail.followerCount }} 人关注
          <template v-if="detail.closeReason"> · 关闭原因：{{ ticketCloseReasonLabel(detail.closeReason) }}</template>
        </p>

        <div v-if="mergedInfo" class="ticket-merged-banner">
          该问题已合并至
          <router-link :to="`/tickets/${mergedInfo.id}`">{{ mergedInfo.ticketNo }} {{ mergedInfo.title }}</router-link>
          ，可在主工单继续跟进。
        </div>

        <div class="ticket-section">
          <h3>问题描述</h3>
          <p class="ticket-pre">{{ detail.description }}</p>
          <template v-if="detail.steps">
            <h3>复现步骤</h3>
            <p class="ticket-pre">{{ detail.steps }}</p>
          </template>
          <template v-if="detail.expectedResult">
            <h3>预期结果</h3>
            <p class="ticket-pre">{{ detail.expectedResult }}</p>
          </template>
          <p v-if="detail.pagePath" class="muted">发生页面：{{ detail.pagePath }}</p>
          <p v-if="Object.keys(detail.environment || {}).length" class="muted">
            使用环境：{{ Object.entries(detail.environment).map(([k, v]) => `${k}: ${v}`).join(' · ') }}
          </p>
          <div v-if="detail.attachments.length" class="ticket-gallery">
            <button
              v-for="attachment in detail.attachments"
              :key="attachment.id"
              type="button"
              class="ticket-thumb"
              :aria-label="`查看截图 ${attachment.name ?? ''}`"
              @click="openLightbox(attachment)"
            >
              <img
                v-if="tickets.attachmentUrls.get(attachment.id)"
                :src="tickets.attachmentUrls.get(attachment.id)"
                :alt="attachment.name ?? '工单截图'"
                loading="lazy"
              />
              <span v-else class="muted">加载中…</span>
            </button>
          </div>
        </div>

        <div v-if="detail.resolution" class="ticket-resolution">
          <h3>解决摘要</h3>
          <p class="ticket-pre">{{ detail.resolution }}</p>
          <p v-if="detail.fixedVersion" class="muted">修复版本：{{ detail.fixedVersion }}</p>
        </div>

        <div class="ticket-section">
          <h3>处理进展</h3>
          <ol class="ticket-timeline">
            <li v-for="event in statusFlow" :key="`${event.action}-${event.createdAt}`">
              <span class="muted">{{ event.createdAt?.slice(0, 16).replace('T', ' ') }}</span>
              <span>
                {{ event.actorName }}
                <template v-if="event.action === 'created'">提交了工单</template>
                <template v-else-if="event.action === 'status_changed'">
                  将状态从 {{ ticketStatusLabel(event.fromStatus ?? '') }} 变更为 {{ ticketStatusLabel(event.toStatus ?? '') }}
                </template>
                <template v-else-if="event.action === 'replied'">回复了工单</template>
                <template v-else-if="event.action === 'admin_replied'">回复了工单</template>
                <template v-else-if="event.action === 'merged'">合并了该工单</template>
                <template v-else-if="event.action === 'unmerged'">取消了合并</template>
                <template v-else-if="event.action === 'received_merge'">接收了一个合并工单</template>
                <template v-else-if="event.action === 'merge_cancelled'">取消了合并工单</template>
                <template v-else-if="event.action === 'pinned'">置顶了工单</template>
                <template v-else-if="event.action === 'unpinned'">取消了置顶</template>
                <template v-else-if="event.action === 'assigned'">设置了处理人</template>
                <template v-else-if="event.action === 'unassigned'">清空了处理人</template>
                <template v-else-if="event.action === 'priority_changed'">调整了优先级</template>
                <template v-else>{{ event.action }}</template>
                <em v-if="event.note">：{{ event.note }}</em>
              </span>
            </li>
          </ol>
        </div>

        <div class="ticket-section">
          <div class="section-head" style="margin-bottom:6px">
            <h3>公开讨论</h3>
            <small class="muted">回复对所有测试用户可见</small>
          </div>
          <p v-if="!tickets.messages.length" class="muted">还没有回复。</p>
          <article v-for="message in tickets.messages" :key="message.id" class="ticket-message">
            <header>
              <strong>{{ message.actorName }}</strong>
              <span v-if="message.isAdmin" class="tag">管理员</span>
              <small class="muted">{{ message.createdAt?.slice(0, 16).replace('T', ' ') }}</small>
            </header>
            <p class="ticket-pre">{{ message.content }}</p>
            <div v-if="message.attachments.length" class="ticket-gallery">
              <button
                v-for="attachment in message.attachments"
                :key="attachment.id"
                type="button"
                class="ticket-thumb"
                @click="openLightbox(attachment)"
              >
                <img
                  v-if="tickets.attachmentUrls.get(attachment.id)"
                  :src="tickets.attachmentUrls.get(attachment.id)"
                  alt="回复截图"
                  loading="lazy"
                />
                <span v-else class="muted">加载中…</span>
              </button>
            </div>
          </article>
          <button
            v-if="messagePage < totalPages"
            class="plain-button"
            style="margin-top:8px"
            @click="loadMoreMessages"
          >加载更早的回复</button>
        </div>

        <div v-if="canReply" class="ticket-section">
          <h3>添加回复</h3>
          <textarea v-model="replyContent" rows="3" maxlength="3000" placeholder="补充你的复现情况或进展（公开可见，最多 3000 字）"></textarea>
          <div class="ticket-reply-tools">
            <label class="ticket-upload">
              <input type="file" accept="image/jpeg,image/png,image/webp" multiple @change="onPickFiles" />
              添加截图
            </label>
            <button class="primary" :disabled="tickets.submitting || uploading" @click="submitReply">
              {{ tickets.submitting ? '发布中...' : '发布回复' }}
            </button>
          </div>
          <div v-if="replyAttachments.length" class="ticket-attachments">
            <span v-for="item in replyAttachments" :key="item.id" class="tag">
              {{ item.name }}
              <button type="button" aria-label="移除截图" @click="removeReplyAttachment(item.id)">✕</button>
            </span>
          </div>
          <p v-if="replyError" class="field-error">{{ replyError }}</p>
        </div>
        <p v-else class="muted ticket-section">工单已关闭，不再接受新回复；如问题仍存在可尝试上方允许的重新处理操作。</p>

        <div v-if="detail.viewer?.isAuthor" class="ticket-section ticket-author-actions">
          <template v-if="detail.status === 'resolved'">
            <button class="primary" @click="confirmResolved">确认解决</button>
            <button @click="reopenOpen = !reopenOpen">仍未解决</button>
          </template>
          <button
            v-if="detail.status !== 'closed' && detail.status !== 'resolved'"
            @click="withdrawOpen = !withdrawOpen"
          >撤回工单</button>
          <p v-if="actionError" class="field-error">{{ actionError }}</p>

          <div v-if="reopenOpen" class="ticket-inline-form">
            <textarea v-model="reopenReason" rows="2" placeholder="请说明为什么仍未解决或需要重新处理（至少 5 个字）"></textarea>
            <button class="primary" :disabled="tickets.submitting" @click="submitReopen">提交重新处理申请</button>
          </div>
          <div v-if="withdrawOpen" class="ticket-inline-form">
            <textarea v-model="withdrawNote" rows="2" placeholder="补充说明（可选），工单关闭后仍保持公开可见"></textarea>
            <button @click="submitWithdraw">确认撤回</button>
          </div>
        </div>
      </section>

      <div v-if="lightboxSrc" class="modal-backdrop" @click.self="closeLightbox">
        <div class="lightbox" role="dialog" aria-label="查看截图">
          <img :src="lightboxSrc" alt="工单截图" @click="closeLightbox" />
          <button class="modal-close" aria-label="关闭" @click="closeLightbox">✕</button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ticket-detail-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.ticket-title-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.ticket-title { font-size: 17px; overflow-wrap: anywhere; }
.ticket-counts { margin: 6px 0 2px; }
.ticket-merged-banner { margin: 10px 0; padding: 10px 12px; border-radius: 8px; background: rgba(47, 128, 237, .1); }
.ticket-section { margin-top: 20px; display: grid; gap: 8px; }
.ticket-section h3 { margin: 0; font-size: 14px; }
.ticket-pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
.ticket-gallery { display: flex; flex-wrap: wrap; gap: 8px; }
.ticket-thumb { width: 132px; height: 92px; padding: 0; overflow: hidden; display: grid; place-items: center; cursor: zoom-in; }
.ticket-thumb img { width: 100%; height: 100%; object-fit: cover; }
.ticket-resolution { margin-top: 18px; padding: 12px; border-radius: 8px; background: rgba(46, 161, 116, .12); display: grid; gap: 6px; }
.ticket-resolution h3 { margin: 0; font-size: 14px; }
.ticket-timeline { margin: 0; padding-left: 18px; display: grid; gap: 6px; }
.ticket-timeline .muted { margin-right: 8px; }
.ticket-message { border-top: 1px solid var(--border-soft, #e5e7eb); padding: 10px 0; display: grid; gap: 6px; }
.ticket-message header { display: flex; align-items: center; gap: 8px; }
.ticket-message p { margin: 0; }
.ticket-reply-tools { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
.ticket-upload { cursor: pointer; }
.ticket-upload input { display: none; }
.ticket-inline-form { display: grid; gap: 8px; margin-top: 8px; }
.lightbox { position: relative; max-width: min(1100px, 92vw); max-height: 90vh; }
.lightbox img { max-width: 100%; max-height: 86vh; display: block; border-radius: 8px; }
.lightbox .modal-close { position: absolute; top: -18px; right: -18px; }
.ticket-attachments { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.ticket-attachments .tag button { margin-left: 4px; border: 0; background: transparent; cursor: pointer; }
</style>
