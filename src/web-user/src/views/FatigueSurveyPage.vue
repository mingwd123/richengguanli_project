<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, BatteryMedium, Check, CircleHelp, Clock3, Gauge, RotateCcw, Settings2, SkipForward } from 'lucide-vue-next'
import { useAppStore } from '../stores/app'
import type { FatigueSurvey, FatigueSurveyComparison, FatigueSurveyToday } from '../types'

const store = useAppStore()
const router = useRouter()
const route = useRoute()
const loading = ref(true)
const saving = ref(false)
const loaded = ref<FatigueSurveyToday | null>(null)
const form = reactive({ score: 50, externalFactorLevel: 0, externalFactorTags: [] as string[] })

const factorOptions = [
  { value: 0, label: '没有明显额外因素' },
  { value: 1, label: '有一些额外因素' },
  { value: 2, label: '额外因素影响明显' },
]
const tagOptions = ['睡眠不足', '身体不适', '情绪压力', '临时额外事务', '高强度运动或出行', '存在未记录任务', '其他']
const scoreAnchors = [
  { score: 0, label: '完全不累，精力充足' },
  { score: 25, label: '有一点累' },
  { score: 50, label: '明显疲劳，但还能继续' },
  { score: 75, label: '很累，需要休息' },
  { score: 100, label: '精疲力尽，不适合继续安排任务' },
]

const daily = computed(() => loaded.value?.daily || store.fatigueDaily)
const profile = computed(() => loaded.value?.profile || store.fatigueProfile)
const existingSurvey = computed<FatigueSurvey | null>(() => {
  const survey = loaded.value?.survey
  return survey && 'score' in survey ? survey as FatigueSurvey : null
})
const comparison = computed<FatigueSurveyComparison | null>(() => {
  const value = loaded.value?.comparison
  if (value && 'predictedScore' in value) return value as FatigueSurveyComparison
  return store.fatigueSurveyComparison
})
const pending = computed(() => Boolean(loaded.value?.pending))
const recurringFactorText = computed(() => (loaded.value?.recurringExternalFactors || [])
  .map(item => `${item.tag}（${item.days} 天）`)
  .join('、'))
const scoreLabel = computed(() => {
  const score = form.score
  if (score < 13) return scoreAnchors[0].label
  if (score < 38) return scoreAnchors[1].label
  if (score < 63) return scoreAnchors[2].label
  if (score < 88) return scoreAnchors[3].label
  return scoreAnchors[4].label
})
const loadPercent = computed(() => {
  const capacity = Number(daily.value?.capacity75 || profile.value?.capacity75 || 0)
  const planned = Number(daily.value?.plannedLoad || 0)
  return capacity > 0 ? Math.min(100, Math.round(planned / capacity * 100)) : 0
})
const actualPercent = computed(() => {
  const capacity = Number(daily.value?.capacity75 || profile.value?.capacity75 || 0)
  const completed = Number(daily.value?.completedLoad || 0)
  return capacity > 0 ? Math.min(100, Math.round(completed / capacity * 100)) : 0
})

function hydrate(data: FatigueSurveyToday) {
  loaded.value = data
  const survey = data.survey && 'score' in data.survey ? data.survey as FatigueSurvey : null
  form.score = survey?.score ?? 50
  form.externalFactorLevel = survey?.externalFactorLevel ?? 0
  if (survey?.externalFactorTagList) {
    form.externalFactorTags = [...survey.externalFactorTagList]
    return
  }
  try {
    const tags = survey?.externalFactorTags ? JSON.parse(survey.externalFactorTags) : []
    form.externalFactorTags = Array.isArray(tags) ? tags.filter(item => typeof item === 'string') : []
  } catch {
    form.externalFactorTags = []
  }
}

async function load(date?: string) {
  loading.value = true
  const data = await store.loadFatigueSurveyToday(date)
  if (data) hydrate(data)
  loading.value = false
}

function toggleTag(tag: string) {
  form.externalFactorTags = form.externalFactorTags.includes(tag)
    ? form.externalFactorTags.filter(item => item !== tag)
    : [...form.externalFactorTags, tag]
}

async function submit() {
  saving.value = true
  try {
    const ok = await store.submitFatigueSurvey({
      localDate: loaded.value?.localDate,
      score: form.score,
      externalFactorLevel: form.externalFactorLevel,
      externalFactorTags: form.externalFactorTags,
    })
    if (ok) await load(loaded.value?.localDate)
  } finally {
    saving.value = false
  }
}

async function snooze() {
  if (await store.snoozeFatigueSurvey(30)) router.back()
}

async function skip() {
  if (await store.skipFatigueSurvey(loaded.value?.localDate)) router.back()
}

function scoreColor(score: number) {
  if (score >= 85) return 'danger'
  if (score >= 70) return 'warning'
  return 'healthy'
}

function modelStageLabel(stage?: string) {
  if (stage === 'personalized') return '已个性化'
  if (stage === 'calibrating') return '校准中'
  return '基础估算'
}

onMounted(() => load(typeof route.query.date === 'string' ? route.query.date : undefined))
</script>

<template>
  <section class="fatigue-page">
    <div v-if="loading" class="detail-panel fatigue-loading"><RotateCcw class="spinning" :size="20" /><span>正在加载疲劳数据...</span></div>
    <template v-else>
      <header class="fatigue-page-head">
        <div>
          <p class="eyebrow">{{ loaded?.localDate }} · {{ loaded?.timezone }}</p>
          <h2>{{ loaded?.isBackfill ? '补填昨日疲劳调查' : '个人日程疲劳调查' }}</h2>
          <p class="hint">{{ pending ? '今天完成了个人日程，记录实际感受可以帮助模型校准。' : existingSurvey ? '这一天的调查已经记录，可以修改。' : '这一天没有待填写的调查。' }}</p>
        </div>
        <div class="fatigue-page-actions">
          <button class="icon-button" title="疲劳评估设置" @click="router.push('/profile/fatigue')"><Settings2 :size="17" /></button>
          <button class="plain-button" title="返回上一页" @click="router.back()"><ArrowLeft :size="16" />返回</button>
        </div>
      </header>

      <div v-if="loaded?.availableDates?.length > 1" class="fatigue-date-switch" aria-label="调查日期">
        <button v-for="date in loaded.availableDates" :key="date" :class="{ active: date === loaded?.localDate }" @click="load(date)">{{ date }}</button>
      </div>

      <div v-if="recurringFactorText" class="fatigue-form-note">
        <CircleHelp :size="15" />
        <span>过去 14 天中，{{ recurringFactorText }}重复出现，可能更接近长期影响。可以把它纳入长期承受基线观察，而不只视为一次性因素。</span>
      </div>

      <div class="fatigue-summary-grid">
        <article class="fatigue-summary-card">
          <span class="fatigue-summary-icon planned"><Gauge :size="19" /></span>
          <div><small>计划负荷</small><strong>{{ daily?.plannedLoad || 0 }} / {{ daily?.capacity75 || profile?.capacity75 || 18 }}</strong><em>{{ loadPercent }}% 上限</em></div>
        </article>
        <article class="fatigue-summary-card">
          <span class="fatigue-summary-icon actual"><Check :size="19" /></span>
          <div><small>已完成负荷</small><strong>{{ daily?.completedLoad || 0 }}</strong><em>{{ actualPercent }}% 上限 · {{ daily?.completedCount || 0 }} 项</em></div>
        </article>
        <article class="fatigue-summary-card">
          <span class="fatigue-summary-icon model"><BatteryMedium :size="19" /></span>
          <div><small>模型状态</small><strong>{{ modelStageLabel(profile?.modelStage) }}</strong><em>{{ profile?.validSurveyDays || 0 }} 个有效调查日</em></div>
        </article>
      </div>

      <div class="fatigue-survey-layout">
        <form class="form-card fatigue-survey-card" @submit.prevent="submit">
          <div class="section-head">
            <div class="section-title"><span class="section-icon fatigue"><BatteryMedium :size="18" /></span><div><h2>{{ existingSurvey ? '修改调查' : '记录实际疲劳' }}</h2><p>只评估个人日程带来的疲劳</p></div></div>
          </div>
          <label class="fatigue-score-field">
            <span><strong>今天这些个人日程给你带来的疲劳程度是多少？</strong><b :class="scoreColor(form.score)">{{ form.score }} · {{ scoreLabel }}</b></span>
            <input v-model.number="form.score" type="range" min="0" max="100" step="1" />
            <div class="fatigue-score-anchors">
              <button v-for="anchor in scoreAnchors" :key="anchor.score" type="button" @click="form.score = anchor.score"><strong>{{ anchor.score }}</strong><span>{{ anchor.label }}</span></button>
            </div>
          </label>

          <fieldset class="fatigue-option-group">
            <legend><strong>额外影响</strong><small>可选</small></legend>
            <div class="fatigue-choice-row">
              <label v-for="option in factorOptions" :key="option.value" :class="['fatigue-choice', { active: form.externalFactorLevel === option.value }]">
                <input v-model.number="form.externalFactorLevel" type="radio" :value="option.value" />
                <span>{{ option.label }}</span>
              </label>
            </div>
          </fieldset>

          <fieldset v-if="form.externalFactorLevel > 0" class="fatigue-option-group">
            <legend><strong>具体原因</strong><small>可多选</small></legend>
            <div class="fatigue-tag-grid">
              <label v-for="tag in tagOptions" :key="tag" :class="['fatigue-choice', { active: form.externalFactorTags.includes(tag) }]">
                <input type="checkbox" :checked="form.externalFactorTags.includes(tag)" @change="toggleTag(tag)" />
                <span>{{ tag }}</span>
              </label>
            </div>
          </fieldset>

          <div class="fatigue-form-note"><CircleHelp :size="15" /><span>轻微额外因素按较低权重学习；身体不适、未记录任务或明显额外影响只保留记录。</span></div>
          <div class="form-actions fatigue-survey-actions">
            <button v-if="loaded?.canSnooze && !existingSurvey" type="button" @click="snooze"><Clock3 :size="16" />稍后 30 分钟</button>
            <button v-if="loaded?.canSkip && !existingSurvey" type="button" @click="skip"><SkipForward :size="16" />跳过</button>
            <button class="primary" :disabled="saving"><Check :size="16" />{{ saving ? '保存中...' : existingSurvey ? '保存修改' : '提交调查' }}</button>
          </div>
        </form>

        <aside class="detail-panel fatigue-insight-panel">
          <div class="section-title"><span class="section-icon time"><Gauge :size="18" /></span><div><h2>结果对照</h2><p>预计负荷与实际反馈</p></div></div>
          <div class="fatigue-meter"><div><span>系统预计</span><strong :class="scoreColor(Number(daily?.predictedScore || 0))">{{ daily?.predictedScore || 0 }} 分</strong></div><span class="fatigue-meter-track"><i :class="scoreColor(Number(daily?.predictedScore || 0))" :style="{ width: `${daily?.predictedScore || 0}%` }"></i></span></div>
          <div v-if="comparison" class="fatigue-comparison-result">
            <div><span>你的反馈</span><strong :class="scoreColor(comparison.feedbackScore)">{{ comparison.feedbackScore }} 分</strong></div>
            <p>{{ comparison.adjustmentText }}</p>
            <small>{{ comparison.modelEligible ? `学习权重 ${comparison.learningWeight}` : '本次不参与自动学习' }}</small>
          </div>
          <dl class="fatigue-insight-list"><div><dt>待办日程</dt><dd>{{ daily?.pendingCount || 0 }} 项</dd></div><div><dt>已完成日程</dt><dd>{{ daily?.completedCount || 0 }} 项</dd></div><div><dt>算法版本</dt><dd>v{{ daily?.algorithmVersion || profile?.algorithmVersion || 2 }}</dd></div></dl>
          <div v-if="daily?.topContributors?.length" class="fatigue-contributors"><h3>主要负荷来源</h3><div v-for="item in daily.topContributors" :key="item.scheduleId"><span>{{ item.title }}</span><em>{{ item.weight }} 点</em></div></div>
          <p class="fatigue-disclaimer">{{ loaded?.disclaimer || profile?.disclaimer }}</p>
        </aside>
      </div>
    </template>
  </section>
</template>
