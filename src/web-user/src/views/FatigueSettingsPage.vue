<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, BatteryMedium, BellRing, ClipboardCheck, Download, RotateCcw, Save, Trash2 } from 'lucide-vue-next'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
const saving = ref(false)
const historyLoading = ref(false)
const preferences = reactive({
  fatigueTrackingEnabled: true,
  fatigueAlertEnabled: true,
  surveyEnabled: true,
  surveyTime: '21:30',
  capacityLocked: false,
})
const range = reactive({ dateFrom: '', dateTo: '' })

const profile = computed(() => store.fatigueProfile)
const history = computed(() => store.fatigueHistory?.list || [])
const maxHistoryScore = computed(() => Math.max(100, ...history.value.map(item => Math.max(Number(item.predictedScore || 0), Number(item.actualScore || 0)))))

function dateKey(date: Date) {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: store.profile?.timezone || 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit'
  })
  return formatter.format(date)
}

function hydratePreferences() {
  if (!profile.value) return
  preferences.fatigueTrackingEnabled = profile.value.fatigueTrackingEnabled
  preferences.fatigueAlertEnabled = profile.value.fatigueAlertEnabled
  preferences.surveyEnabled = profile.value.surveyEnabled
  preferences.surveyTime = String(profile.value.surveyTime || '21:30').slice(0, 5)
  preferences.capacityLocked = profile.value.capacityLocked
}

async function loadHistory() {
  historyLoading.value = true
  try {
    await store.loadFatigueHistory(range.dateFrom, range.dateTo)
  } finally {
    historyLoading.value = false
  }
}

async function savePreferences() {
  saving.value = true
  try {
    await store.updateFatiguePreferences({ ...preferences })
  } finally {
    saving.value = false
  }
}

async function resetModel() {
  if (!window.confirm('确认重置个性化疲劳模型？已有调查记录会保留，但不再用于后续自动学习。')) return
  if (await store.resetFatigueProfile()) hydratePreferences()
}

async function deleteHistory() {
  if (!window.confirm('确认删除全部疲劳调查历史及团队完成疲劳快照？完成日期仍会保留，但疲劳等级和权重无法恢复。')) return
  await store.deleteFatigueSurveyHistory()
  await loadHistory()
}

function stageLabel(stage?: string) {
  if (stage === 'personalized') return '已个性化'
  if (stage === 'calibrating') return '校准中'
  return '基础估算'
}

function confidenceLabel(value?: string) {
  if (value === 'high') return '高'
  if (value === 'medium') return '中'
  return '低'
}

function scoreClass(score?: number) {
  const value = Number(score || 0)
  if (value >= 85) return 'danger'
  if (value >= 70) return 'warning'
  return 'healthy'
}

watch(profile, hydratePreferences)

onMounted(async () => {
  const now = new Date()
  range.dateTo = dateKey(now)
  range.dateFrom = dateKey(new Date(now.getTime() - 29 * 24 * 60 * 60 * 1000))
  await store.loadFatigueProfile()
  hydratePreferences()
  await loadHistory()
})
</script>

<template>
  <section class="fatigue-page fatigue-settings-page">
    <header class="fatigue-page-head">
      <div>
        <p class="eyebrow">个性化模型 · v{{ profile?.algorithmVersion || 2 }}</p>
        <h2>疲劳评估设置</h2>
        <p class="hint">{{ stageLabel(profile?.modelStage) }} · {{ profile?.validSurveyDays || 0 }} 个有效调查日</p>
      </div>
      <button class="plain-button" title="返回个人中心" @click="router.push('/profile')"><ArrowLeft :size="16" />返回</button>
    </header>

    <div class="fatigue-settings-layout">
      <div class="fatigue-settings-main">
        <section class="fatigue-settings-panel">
          <div class="section-title"><span class="section-icon fatigue"><BatteryMedium :size="18" /></span><div><h2>评估与提醒</h2><p>个人日程疲劳偏好</p></div></div>
          <div class="fatigue-setting-rows">
            <label class="fatigue-setting-row">
              <span><strong>疲劳追踪</strong><small>每日负荷计算与历史汇总</small></span>
              <input v-model="preferences.fatigueTrackingEnabled" type="checkbox" />
            </label>
            <label class="fatigue-setting-row" :class="{ disabled: !preferences.fatigueTrackingEnabled }">
              <span><strong>负荷提醒</strong><small>较疲劳、高负荷与可能过载</small></span>
              <input v-model="preferences.fatigueAlertEnabled" type="checkbox" :disabled="!preferences.fatigueTrackingEnabled" />
            </label>
            <label class="fatigue-setting-row" :class="{ disabled: !preferences.fatigueTrackingEnabled }">
              <span><strong>日终调查</strong><small>个人日程带来的实际疲劳反馈</small></span>
              <input v-model="preferences.surveyEnabled" type="checkbox" :disabled="!preferences.fatigueTrackingEnabled" />
            </label>
            <label class="fatigue-setting-row fatigue-time-setting" :class="{ disabled: !preferences.surveyEnabled || !preferences.fatigueTrackingEnabled }">
              <span><strong>调查时间</strong><small>{{ store.profile?.timezone || 'Asia/Shanghai' }}</small></span>
              <input v-model="preferences.surveyTime" type="time" :disabled="!preferences.surveyEnabled || !preferences.fatigueTrackingEnabled" />
            </label>
            <label class="fatigue-setting-row">
              <span><strong>锁定个性化参数</strong><small>保留当前上限与五档系数</small></span>
              <input v-model="preferences.capacityLocked" type="checkbox" />
            </label>
          </div>
          <div class="form-actions"><button class="primary" :disabled="saving" @click="savePreferences"><Save :size="16" />{{ saving ? '保存中...' : '保存设置' }}</button></div>
        </section>

        <section class="fatigue-settings-panel fatigue-history-panel">
          <div class="fatigue-history-head">
            <div class="section-title"><span class="section-icon time"><ClipboardCheck :size="18" /></span><div><h2>负荷与反馈历史</h2><p>{{ range.dateFrom }} 至 {{ range.dateTo }}</p></div></div>
            <div class="fatigue-history-actions">
              <input v-model="range.dateFrom" type="date" aria-label="开始日期" />
              <input v-model="range.dateTo" type="date" aria-label="结束日期" />
              <button class="icon-button" title="刷新历史" :disabled="historyLoading" @click="loadHistory"><RotateCcw :class="{ spinning: historyLoading }" :size="17" /></button>
              <button class="icon-button" title="导出 CSV" @click="store.exportFatigueHistory(range.dateFrom, range.dateTo)"><Download :size="17" /></button>
            </div>
          </div>
          <div v-if="history.length" class="fatigue-history-list">
            <article v-for="item in history" :key="item.localDate" class="fatigue-history-row">
              <div class="fatigue-history-date"><strong>{{ item.localDate }}</strong><small>{{ item.completedCount }} 项完成 · {{ item.completedLoad }} 点</small></div>
              <div class="fatigue-history-bars">
                <div><span>预计</span><i><b :class="scoreClass(item.predictedScore)" :style="{ width: `${Number(item.predictedScore || 0) / maxHistoryScore * 100}%` }"></b></i><em>{{ item.predictedScore }}</em></div>
                <div><span>反馈</span><i><b v-if="item.actualScore !== undefined" class="actual" :style="{ width: `${Number(item.actualScore || 0) / maxHistoryScore * 100}%` }"></b></i><em>{{ item.actualScore ?? '-' }}</em></div>
              </div>
              <span :class="['tag', item.modelEligible ? 'blue' : '']">{{ item.modelEligible ? `学习权重 ${item.learningWeight}` : item.actualScore === undefined ? '未调查' : '仅保留' }}</span>
            </article>
          </div>
          <div v-else class="compact-empty">{{ historyLoading ? '正在加载历史...' : '当前日期范围内暂无记录' }}</div>
        </section>

        <section class="fatigue-settings-panel fatigue-data-actions">
          <div class="section-title"><span class="section-icon"><RotateCcw :size="18" /></span><div><h2>模型与数据</h2><p>重置参数或删除调查历史</p></div></div>
          <div class="fatigue-danger-actions">
            <button @click="resetModel"><RotateCcw :size="16" />重置个性化模型</button>
            <button class="danger-action" @click="deleteHistory"><Trash2 :size="16" />删除调查历史</button>
          </div>
        </section>
      </div>

      <aside class="detail-panel fatigue-model-panel">
        <div class="section-title"><span class="section-icon time"><BellRing :size="18" /></span><div><h2>当前模型</h2><p>{{ stageLabel(profile?.modelStage) }}</p></div></div>
        <dl class="fatigue-insight-list">
          <div><dt>75 分承受上限</dt><dd>{{ profile?.capacity75 ?? 18 }} 点</dd></div>
          <div><dt>有效样本</dt><dd>{{ profile?.validSurveyDays || 0 }} 天</dd></div>
          <div><dt>置信度</dt><dd>{{ confidenceLabel(profile?.confidence) }}</dd></div>
          <div><dt>参数状态</dt><dd>{{ profile?.capacityLocked ? '已锁定' : '自动校准' }}</dd></div>
        </dl>
        <div class="fatigue-weight-list">
          <h3>五档当前系数</h3>
          <div v-for="level in 5" :key="level"><span><i :class="`level-${level}`"></i>疲劳 {{ level }}</span><strong>{{ profile?.weights?.[String(level)] ?? [1, 2, 3, 5, 8][level - 1] }}</strong></div>
        </div>
        <p class="fatigue-disclaimer">{{ profile?.disclaimer || '疲劳结果仅用于个人日程规划参考，不构成医疗诊断或健康建议。' }}</p>
      </aside>
    </div>
  </section>
</template>
