<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  BatteryMedium,
  Check,
  CircleHelp,
  Clock3,
  Gauge,
  RefreshCw,
  SkipForward,
} from 'lucide-vue-next'
import { useAppStore } from '@web/stores/app'
import type { FatigueSurvey, FatigueSurveyToday } from '@web/types'

const props = defineProps<{ localDate?: string }>()
const emit = defineEmits<{
  close: []
  updated: []
}>()

const store = useAppStore()
const loading = ref(true)
const saving = ref(false)
const actionBusy = ref(false)
const loaded = ref<FatigueSurveyToday | null>(null)
const draftReady = ref(false)
const form = reactive({
  score: 50,
  externalFactorLevel: 0,
  externalFactorTags: [] as string[],
})

const DRAFT_KEY_PREFIX = 'dayliane_desktop_quick_fatigue_survey_'
const tagOptions = ['睡眠不足', '身体不适', '情绪压力', '临时额外事务', '高强度运动或出行', '存在未记录任务', '其他']
const scoreAnchors = [
  { score: 0, label: '精力充足' },
  { score: 25, label: '有一点累' },
  { score: 50, label: '明显疲劳' },
  { score: 75, label: '需要休息' },
  { score: 100, label: '精疲力尽' },
]

const daily = computed(() => loaded.value?.daily || store.fatigueDaily)
const profile = computed(() => loaded.value?.profile || store.fatigueProfile)
const existingSurvey = computed<FatigueSurvey | null>(() => {
  const survey = loaded.value?.survey
  return survey && 'score' in survey ? survey as FatigueSurvey : null
})
const scoreLabel = computed(() => {
  if (form.score < 13) return '完全不累，精力充足'
  if (form.score < 38) return '有一点累'
  if (form.score < 63) return '明显疲劳，但还能继续'
  if (form.score < 88) return '很累，需要休息'
  return '精疲力尽，不适合继续安排任务'
})
const recurringFactorText = computed(() => (loaded.value?.recurringExternalFactors || [])
  .map(item => `${item.tag}（${item.days} 天）`)
  .join('、'))
const hasExternalFactor = computed({
  get: () => form.externalFactorLevel > 0,
  set: value => {
    form.externalFactorLevel = value ? Math.max(1, form.externalFactorLevel) : 0
    if (!value) form.externalFactorTags = []
  },
})

function draftKey(localDate?: string) {
  const userId = store.profile?.id
  return userId && localDate ? `${DRAFT_KEY_PREFIX}${userId}_${localDate}` : ''
}

function clearDraft(localDate?: string) {
  const key = draftKey(localDate)
  if (key) localStorage.removeItem(key)
}

function readSurveyTags(survey: FatigueSurvey | null) {
  if (survey?.externalFactorTagList) return [...survey.externalFactorTagList]
  try {
    const parsed = survey?.externalFactorTags ? JSON.parse(survey.externalFactorTags) : []
    return Array.isArray(parsed) ? parsed.filter(item => typeof item === 'string') : []
  } catch {
    return []
  }
}

function hydrate(data: FatigueSurveyToday) {
  draftReady.value = false
  loaded.value = data
  const survey = data.survey && 'score' in data.survey ? data.survey as FatigueSurvey : null
  if (survey) {
    form.score = survey.score
    form.externalFactorLevel = survey.externalFactorLevel
    form.externalFactorTags = readSurveyTags(survey)
    clearDraft(data.localDate)
    draftReady.value = true
    return
  }

  const key = draftKey(data.localDate)
  try {
    const draft = key ? JSON.parse(localStorage.getItem(key) || 'null') : null
    form.score = Number.isInteger(draft?.score) && draft.score >= 0 && draft.score <= 100 ? draft.score : 50
    form.externalFactorLevel = [0, 1, 2].includes(draft?.externalFactorLevel) ? draft.externalFactorLevel : 0
    form.externalFactorTags = Array.isArray(draft?.externalFactorTags)
      ? draft.externalFactorTags.filter((item: unknown) => typeof item === 'string' && tagOptions.includes(item as string))
      : []
  } catch {
    form.score = 50
    form.externalFactorLevel = 0
    form.externalFactorTags = []
  }
  draftReady.value = true
}

async function load(date = props.localDate) {
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
  if (!loaded.value || saving.value) return
  saving.value = true
  try {
    const ok = await store.submitFatigueSurvey({
      localDate: loaded.value.localDate,
      score: form.score,
      externalFactorLevel: form.externalFactorLevel,
      externalFactorTags: form.externalFactorTags,
    })
    if (!ok) return
    clearDraft(loaded.value.localDate)
    emit('updated')
    emit('close')
  } finally {
    saving.value = false
  }
}

async function snooze() {
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    if (await store.snoozeFatigueSurvey(30)) {
      emit('updated')
      emit('close')
    }
  } finally {
    actionBusy.value = false
  }
}

async function skip() {
  if (!loaded.value || actionBusy.value) return
  actionBusy.value = true
  try {
    if (await store.skipFatigueSurvey(loaded.value.localDate)) {
      clearDraft(loaded.value.localDate)
      emit('updated')
      emit('close')
    }
  } finally {
    actionBusy.value = false
  }
}

watch(form, value => {
  if (!draftReady.value || existingSurvey.value || !loaded.value) return
  const key = draftKey(loaded.value.localDate)
  if (key) localStorage.setItem(key, JSON.stringify({
    score: value.score,
    externalFactorLevel: value.externalFactorLevel,
    externalFactorTags: value.externalFactorTags,
  }))
}, { deep: true })

watch(() => props.localDate, date => {
  if (date && date !== loaded.value?.localDate) load(date)
})

onMounted(() => load())
</script>

<template>
  <section class="quick-fatigue-survey-view">
    <div v-if="loading" class="quick-detail-loading">
      <RefreshCw class="spinning" :size="20" />
      <span>正在加载调查...</span>
    </div>

    <template v-else-if="loaded">
      <div v-if="loaded.availableDates.length > 1" class="quick-survey-date-switch" aria-label="调查日期">
        <button v-for="date in loaded.availableDates" :key="date" type="button" :class="{ active: date === loaded.localDate }" @click="load(date)">{{ date }}</button>
      </div>

      <div class="quick-survey-summary">
        <div><Gauge :size="16" /><span>系统预计</span><strong>{{ daily?.predictedScore || 0 }} 分</strong></div>
        <div><Check :size="16" /><span>完成负荷</span><strong>{{ daily?.completedLoad || 0 }} 点</strong></div>
        <div><BatteryMedium :size="16" /><span>模型阶段</span><strong>{{ profile?.modelStage === 'personalized' ? '已个性化' : profile?.modelStage === 'calibrating' ? '校准中' : '基础估算' }}</strong></div>
      </div>

      <div v-if="recurringFactorText" class="quick-survey-note">
        <CircleHelp :size="15" />
        <span>过去 14 天中，{{ recurringFactorText }}重复出现，可作为长期承受基线继续观察。</span>
      </div>

      <form class="quick-survey-form" @submit.prevent="submit">
        <label class="quick-survey-score">
          <span><strong>{{ existingSurvey ? '修改实际疲劳' : '今天实际有多累？' }}</strong><b>{{ form.score }}</b></span>
          <small>{{ scoreLabel }}</small>
          <input v-model.number="form.score" type="range" min="0" max="100" step="1" aria-label="实际疲劳分数" />
        </label>

        <div class="quick-survey-anchors" aria-label="疲劳分数锚点">
          <button v-for="anchor in scoreAnchors" :key="anchor.score" type="button" :title="anchor.label" @click="form.score = anchor.score">
            <strong>{{ anchor.score }}</strong><span>{{ anchor.label }}</span>
          </button>
        </div>

        <label class="quick-survey-toggle">
          <span><strong>存在额外影响</strong><small>睡眠、身体或临时事务等</small></span>
          <input v-model="hasExternalFactor" type="checkbox" role="switch" />
        </label>

        <template v-if="hasExternalFactor">
          <div class="quick-survey-factor-level" role="group" aria-label="额外影响程度">
            <button type="button" :class="{ active: form.externalFactorLevel === 1 }" @click="form.externalFactorLevel = 1">有一些影响</button>
            <button type="button" :class="{ active: form.externalFactorLevel === 2 }" @click="form.externalFactorLevel = 2">影响很明显</button>
          </div>
          <div class="quick-survey-tags" aria-label="额外影响原因">
            <label v-for="tag in tagOptions" :key="tag" :class="{ active: form.externalFactorTags.includes(tag) }">
              <input type="checkbox" :checked="form.externalFactorTags.includes(tag)" @change="toggleTag(tag)" />
              <span>{{ tag }}</span>
            </label>
          </div>
        </template>

        <div class="quick-survey-note">
          <CircleHelp :size="15" />
          <span>这里只评估个人日程带来的疲劳，不包含团队任务，也不是医疗判断。</span>
        </div>

        <div class="quick-survey-actions">
          <button v-if="loaded.canSnooze && !existingSurvey" type="button" :disabled="actionBusy || saving" @click="snooze"><Clock3 :size="15" />稍后提醒</button>
          <button v-if="loaded.canSkip && !existingSurvey" type="button" :disabled="actionBusy || saving" @click="skip"><SkipForward :size="15" />今天跳过</button>
          <button class="primary" :disabled="saving || actionBusy"><Check :size="16" />{{ saving ? '提交中...' : existingSurvey ? '保存修改' : '提交调查' }}</button>
        </div>
      </form>
    </template>

    <div v-else class="quick-empty-state">
      <BatteryMedium :size="23" />
      <strong>调查数据加载失败</strong>
      <button type="button" @click="load()">重试</button>
    </div>
  </section>
</template>
