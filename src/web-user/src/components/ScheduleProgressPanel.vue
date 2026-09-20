<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, useAttrs, watch } from 'vue'
import { useAppStore } from '../stores/app'
import { formatProgressPercent, progressCompletionActionLabel, progressSubmitDialogTitle } from '../utils/helpers'
import ScheduleLevelControl from './ScheduleLevelControl.vue'
import type { ScheduleProgressItem, ScheduleStatus } from '../types'

/**
 * 每日进度面板（网页端与桌面端共用，避免两端各实现一套规则）。
 * 自己负责拉取进度数据，提交/修正/补录后通过 updated 事件让父级刷新日程详情。
 */
const props = withDefaults(defineProps<{
  scheduleId: number
  status: ScheduleStatus
  defaultFatigueLevel?: number
  editable?: boolean
}>(), { defaultFatigueLevel: 3, editable: true })

const emit = defineEmits<{ (e: 'updated'): void }>()

// 组件有两个根节点（面板 + 两个弹窗），class 不会自动落到面板上，
// 因此显式把调用方传入的 class 绑到面板根节点，避免 .detail-progress-panel /
// .quick-detail-progress 这类外部布局样式失效。
defineOptions({ inheritAttrs: false })
const attrs = useAttrs()
const panelClass = computed(() => (attrs.class as string | undefined) || undefined)

const store = useAppStore()
const fatigueNames = ['几乎不累', '轻微消耗', '一般', '比较劳累', '非常劳累']

const progress = computed(() => store.scheduleProgress)
const items = computed<ScheduleProgressItem[]>(() => progress.value?.items || [])
const percent = computed(() => Number(progress.value?.progressPercent ?? 0))
const percentText = computed(() => formatProgressPercent(percent.value))
const fatigueTrackingOn = computed(() => progress.value?.fatigueTrackingEnabled !== false)
const canSubmit = computed(() => (progress.value?.canSubmitProgress ?? props.status === 'pending') && props.editable)
const canBackfill = computed(() => (progress.value?.canBackfill ?? true) && props.editable)
const backfillMinDate = computed(() => progress.value?.backfillMinDate || '')
const backfillMaxDate = computed(() => progress.value?.backfillMaxDate || '')
const completedDate = computed(() => progress.value?.completedDate || '')

// 累计已满 100% 但仍是待办时，弹窗是「把任务标记完成」而不是「再提交一段进度」。
const dialogTitle = computed(() => progressSubmitDialogTitle(percent.value, submitTarget.value))

const submitOpen = ref(false)
const submitValue = ref(0)
const submitLevel = ref<number | null>(null)
const submitTarget = ref(0)
const submitting = ref(false)

const editOpen = ref(false)
const editItem = ref<ScheduleProgressItem | null>(null)
const editDate = ref('')
const editValue = ref(0)
const editLevel = ref<number | null>(null)
const editing = ref(false)

// 补录/修正预览：把本次增量插回日期顺序，算出新的累计进度与完成日。
const editPreview = computed(() => {
  const delta = Number(editValue.value)
  const rows = items.value
    .filter(item => item.progressDate !== editItem.value?.progressDate)
    .map(item => ({ date: item.progressDate, delta: Number(item.progressDelta ?? 0) }))
  if (Number.isFinite(delta) && delta > 0 && editDate.value) {
    rows.push({ date: editDate.value, delta })
  }
  rows.sort((a, b) => a.date.localeCompare(b.date))
  let running = 0
  let completionDate = ''
  for (const row of rows) {
    running += row.delta
    if (!completionDate && running >= 100) completionDate = row.date
  }
  return {
    total: Math.min(100, Math.round(running * 10) / 10),
    completionDate,
    overflow: running > 100,
    dates: rows.map(row => row.date)
  }
})

async function load() {
  await store.loadScheduleProgress(props.scheduleId)
}

function openSubmit(target?: number) {
  submitValue.value = target ?? Math.min(100, Math.max(0, percent.value))
  submitTarget.value = target ?? percent.value
  submitLevel.value = props.defaultFatigueLevel
  submitOpen.value = true
}

async function submit() {
  const value = Number(submitValue.value)
  if (!Number.isFinite(value) || value < 0 || value > 100) return store.notify('进度需在 0～100 之间')
  if (value < percent.value) return store.notify('进度只能增加；减少历史进度请使用「修正」')
  const hasDelta = value > percent.value
  if (hasDelta && fatigueTrackingOn.value && !submitLevel.value) return store.notify('请选择当日疲劳程度')
  submitting.value = true
  // 进度任务只能在待办态提交；提交到 100% 即完成任务（含累计已满 100%、只补记完成的情况）。
  const completes = value === 100 && canSubmit.value
  // 追踪关闭或没有新增进度时不下发疲劳等级。
  const level = hasDelta && fatigueTrackingOn.value ? submitLevel.value : null
  const ok = await store.submitScheduleProgress(props.scheduleId, value, level)
  submitting.value = false
  if (!ok) return
  submitOpen.value = false
  emit('updated')
  store.notify(completes ? '累计进度已达 100%，任务已完成' : '每日进度已更新')
}

function openCorrection(item: ScheduleProgressItem) {
  editItem.value = item
  editDate.value = item.progressDate
  editValue.value = Number(item.progressDelta ?? 0)
  editLevel.value = item.fatigueLevel || props.defaultFatigueLevel
  editOpen.value = true
}

function openBackfill() {
  editItem.value = null
  editDate.value = backfillMaxDate.value
  editValue.value = 0
  editLevel.value = props.defaultFatigueLevel
  editOpen.value = true
}

async function saveEdit() {
  const date = editDate.value
  if (!date) return store.notify('请选择日期')
  if (backfillMinDate.value && date < backfillMinDate.value) return store.notify(`补录日期不能早于 ${backfillMinDate.value}`)
  if (backfillMaxDate.value && date > backfillMaxDate.value) return store.notify(`补录日期不能晚于 ${backfillMaxDate.value}`)
  const value = Number(editValue.value)
  if (!Number.isFinite(value) || value < 0 || value > 100) return store.notify('当日增量需在 0～100 之间')
  if (editPreview.value.overflow) return store.notify('累计进度不能超过 100%，请先调整其它日期')
  if (value > 0 && fatigueTrackingOn.value && !editLevel.value) return store.notify('请选择当日疲劳程度')
  editing.value = true
  const level = value > 0 && fatigueTrackingOn.value ? editLevel.value : null
  const ok = await store.correctScheduleProgress(props.scheduleId, date, value, level)
  editing.value = false
  if (!ok) return
  editOpen.value = false
  emit('updated')
}

onMounted(load)
watch(() => props.scheduleId, load)
onUnmounted(() => store.resetScheduleProgress())

// 供桌面快捷详情等外部入口触发「完成剩余进度 / 补录」。
defineExpose({ openSubmit, openBackfill })
</script>

<template>
  <div class="progress-panel" :class="panelClass" data-testid="progress-panel">
    <div class="progress-head">
      <div>
        <span class="muted">每日进度</span>
        <strong style="margin-left:8px">{{ percentText }}</strong>
        <span v-if="completedDate" class="muted" style="margin-left:8px">完成日 {{ completedDate }}</span>
      </div>
      <div class="muted">累计完成负荷 {{ progress?.totalCompletedLoad ?? 0 }} 点</div>
    </div>
    <div class="progress-track"><span :style="{ width: percentText }"></span></div>
    <p v-if="status === 'cancelled'" class="muted">
      任务已取消：可以查看和修正已经产生的进度记录，恢复任务后才能继续提交新进度或完成。
    </p>
    <p v-if="store.scheduleProgressError" class="muted" style="color:#e11d48">
      每日进度加载失败：{{ store.scheduleProgressError }}
    </p>
    <p v-if="store.scheduleProgressLoading && !items.length" class="muted">正在加载进度记录…</p>
    <p v-if="!store.scheduleProgressLoading && !store.scheduleProgressError && !items.length" class="muted">
      还没有进度记录。按天提交累计完成比例，并选择当天推进对应的疲劳程度即可。
    </p>
    <div v-if="items.length" class="progress-table-wrap">
      <table class="progress-table">
        <thead>
          <tr><th>日期</th><th>当日增量</th><th>累计</th><th>当日疲劳</th><th>完成负荷</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.progressDate">
            <td data-label="日期">{{ item.progressDate }}</td>
            <td data-label="当日增量">{{ formatProgressPercent(item.progressDelta) }}</td>
            <td data-label="累计">{{ formatProgressPercent(item.cumulativeProgress) }}</td>
            <td data-label="当日疲劳">
              <span v-if="item.fatigueLevel" class="tag danger">{{ item.fatigueLevel }} · {{ fatigueNames[item.fatigueLevel - 1] }}</span>
              <span v-else class="muted">未记录</span>
            </td>
            <td data-label="完成负荷">{{ item.completedLoad }} 点</td>
            <td>
              <button v-if="canBackfill" type="button" class="plain-button" @click="openCorrection(item)">修正</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="canBackfill" class="progress-panel-actions">
      <button type="button" class="plain-button" @click="openBackfill()">补录历史日期</button>
      <small class="muted">可补录 {{ backfillMinDate || '任务起始日' }} 至 {{ backfillMaxDate || '今天' }}</small>
    </div>
    <div v-if="canSubmit" class="progress-panel-actions">
      <button v-if="percent < 100" type="button" class="plain-button" @click="openSubmit()">提交进度</button>
      <!-- 累计已满 100% 但任务仍是待办（例如取消后恢复、或修正回退再补回）：需要一个明确的完成入口。 -->
      <button type="button" class="plain-button" @click="openSubmit(100)">
        {{ progressCompletionActionLabel(percent) }}
      </button>
    </div>
    <small class="muted">每日完成负荷 = 当日增量 ÷ 100 × 提交时的个性化权重；进度达 100% 自动完成任务。</small>
  </div>

  <div v-if="submitOpen" class="modal-backdrop" @click.self="submitOpen = false">
    <section class="modal-panel">
      <div class="modal-head">
        <h2>{{ dialogTitle }}</h2>
        <button class="modal-close" aria-label="关闭" @click="submitOpen = false">✕</button>
      </div>
      <p class="muted" style="margin-bottom:12px">
        <template v-if="percent >= 100">
          当前累计 {{ percentText }}，任务仍为待办。提交后任务将标记为完成，本次不会产生新的疲劳负荷。
        </template>
        <template v-else>
          当前累计 {{ percentText }}。填写今天推进后的累计完成比例，未满 100% 无法直接完成任务。
        </template>
      </p>
      <label>
        累计进度（%）
        <input v-model.number="submitValue" type="number" min="0" max="100" step="1" data-testid="progress-submit-value" />
      </label>
      <ScheduleLevelControl
        v-if="fatigueTrackingOn && Number(submitValue) > percent"
        v-model="submitLevel"
        label="当日疲劳度"
        :labels="fatigueNames"
        :weights="store.fatigueProfile?.weights"
      />
      <p v-else class="muted">疲劳追踪已关闭或当日没有新增进度，本次不产生疲劳负荷。</p>
      <div class="form-actions">
        <button type="button" @click="submitOpen = false">取消</button>
        <button class="primary" :disabled="submitting" @click="submit">{{ submitting ? '提交中...' : '提交' }}</button>
      </div>
    </section>
  </div>

  <div v-if="editOpen" class="modal-backdrop" @click.self="editOpen = false">
    <section class="modal-panel">
      <div class="modal-head">
        <h2>{{ editItem ? `修正 ${editItem.progressDate} 的进度` : '补录历史进度' }}</h2>
        <button class="modal-close" aria-label="关闭" @click="editOpen = false">✕</button>
      </div>
      <p v-if="editItem" class="muted" style="margin-bottom:12px">
        该日当前记录：增量 {{ formatProgressPercent(editItem.progressDelta) }}、累计 {{ formatProgressPercent(editItem.cumulativeProgress) }}。
        改为 0 会删除该日记录；累计回退到不足 100% 时任务会重新打开。
      </p>
      <p v-else class="muted" style="margin-bottom:12px">
        补录一条此前没有记录的日期，系统会按日期顺序重算后续累计进度。
      </p>
      <label v-if="!editItem">
        补录日期
        <input v-model="editDate" type="date" :min="backfillMinDate || undefined" :max="backfillMaxDate || undefined" data-testid="progress-backfill-date" />
      </label>
      <label>
        当日增量（%）
        <input v-model.number="editValue" type="number" min="0" max="100" step="1" />
      </label>
      <ScheduleLevelControl
        v-if="fatigueTrackingOn && Number(editValue) > 0"
        v-model="editLevel"
        label="当日疲劳度"
        :labels="fatigueNames"
        :weights="store.fatigueProfile?.weights"
      />
      <p v-else-if="Number(editValue) > 0" class="muted">疲劳追踪已关闭，本次只记录进度，不产生疲劳负荷。</p>
      <div class="progress-preview" :class="{ invalid: editPreview.overflow }">
        <span class="muted">保存后累计进度：</span>
        <strong>{{ formatProgressPercent(editPreview.total) }}</strong>
        <template v-if="editPreview.overflow">
          <span> · 超过 100%，请先调整其它日期</span>
        </template>
        <template v-else-if="editPreview.completionDate">
          <span> · 达到 100%，任务将在 {{ editPreview.completionDate }} 完成</span>
        </template>
        <template v-else-if="editItem && percent >= 100">
          <span> · 将低于 100%，任务会重新打开</span>
        </template>
      </div>
      <small v-if="editPreview.dates.length" class="muted">受影响日期：{{ editPreview.dates.join('、') }}</small>
      <div class="form-actions">
        <button type="button" @click="editOpen = false">取消</button>
        <button class="primary" :disabled="editing" @click="saveEdit">{{ editing ? '保存中...' : '保存' }}</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.progress-panel {
  display: grid;
  gap: 12px;
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  padding: 16px;
}
.progress-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}
.progress-track {
  height: 8px;
  border-radius: 999px;
  background: rgba(47, 128, 237, 0.14);
  overflow: hidden;
}
.progress-track span {
  display: block;
  height: 100%;
  background: #2f80ed;
}
.progress-table-wrap {
  overflow-x: auto;
}
.progress-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.progress-table th,
.progress-table td {
  text-align: left;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border, #e5e7eb);
}
.progress-panel-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.progress-preview {
  border-radius: 8px;
  background: rgba(47, 128, 237, 0.08);
  padding: 8px 10px;
  font-size: 13px;
}
.progress-preview.invalid {
  background: rgba(225, 29, 72, 0.1);
  color: #e11d48;
}
.plain-button {
  border: 1px solid var(--border, #e5e7eb);
  background: transparent;
  border-radius: 6px;
  padding: 2px 10px;
  cursor: pointer;
}
.muted {
  color: #64748b;
}
/* P09：窄屏改为纵向记录卡片，避免页面整体横向滚动。 */
@media (max-width: 640px) {
  .progress-table thead {
    display: none;
  }
  .progress-table,
  .progress-table tbody,
  .progress-table tr,
  .progress-table td {
    display: block;
    width: 100%;
  }
  .progress-table tr {
    border: 1px solid var(--border, #e5e7eb);
    border-radius: 8px;
    padding: 8px 10px;
    margin-bottom: 8px;
  }
  .progress-table td {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    padding: 3px 0;
    border-bottom: 0;
    font-size: 13px;
  }
  .progress-table td::before {
    content: attr(data-label);
    color: #64748b;
    flex: 0 0 auto;
  }
  .progress-table td:last-child {
    justify-content: flex-end;
  }
  .progress-table td:last-child::before {
    content: none;
  }
}
</style>
