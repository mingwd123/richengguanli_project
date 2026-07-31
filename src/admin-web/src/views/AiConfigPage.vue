<script setup>
import { onMounted, ref, watch } from 'vue'
import { useAdminStore } from '../stores/admin'

const store = useAdminStore()
const form = ref({ provider: '', modelName: '', apiBaseUrl: '', remark: '' })

onMounted(async () => {
  await store.fetchAiConfig()
  if (store.aiConfig) {
    form.value = {
      provider: store.aiConfig.provider || '',
      modelName: store.aiConfig.modelName || '',
      apiBaseUrl: store.aiConfig.apiBaseUrl || '',
      remark: store.aiConfig.remark || '',
    }
  }
})

watch(() => store.aiConfig, (config) => {
  if (config) {
    form.value.provider = config.provider || ''
    form.value.modelName = config.modelName || ''
    form.value.apiBaseUrl = config.apiBaseUrl || ''
    form.value.remark = config.remark || ''
  }
})

async function saveConfig() {
  await store.updateAiConfig({
    provider: form.value.provider,
    modelName: form.value.modelName,
    apiBaseUrl: form.value.apiBaseUrl,
    remark: form.value.remark,
  })
}

async function toggleEnabled() {
  if (!store.aiConfig) return
  await store.updateAiEnabled(!store.aiConfig.enabled)
}

async function runTest() {
  store.aiTestResult = ''
  await store.testAi()
}

function refresh() {
  store.fetchAiConfig()
}
</script>

<template>
  <div class="resource-page">
    <header class="topbar">
      <div>
        <h1>AI 配置</h1>
        <p>查看和修改 AI 服务提供商的配置。</p>
      </div>
      <button class="primary" @click="refresh">刷新</button>
    </header>

    <section v-if="store.aiConfigLoading && !store.aiConfig" style="padding:40px;text-align:center;color:#718096">加载中...</section>

    <section v-else class="form-card" style="margin-top:16px">
      <div class="create-panel">
        <h2>服务商配置</h2>
        <form @submit.prevent="saveConfig" class="create-form" style="display:grid;gap:16px">
          <div>
            <label style="display:block;margin-bottom:4px;font-weight:700;font-size:13px;color:#475569">服务商</label>
            <input v-model="form.provider" placeholder="deepseek" />
          </div>
          <div>
            <label style="display:block;margin-bottom:4px;font-weight:700;font-size:13px;color:#475569">模型名称</label>
            <input v-model="form.modelName" placeholder="deepseek-chat" />
          </div>
          <div>
            <label style="display:block;margin-bottom:4px;font-weight:700;font-size:13px;color:#475569">API Base URL</label>
            <input v-model="form.apiBaseUrl" placeholder="https://api.deepseek.com/v1" />
          </div>
          <div>
            <label style="display:block;margin-bottom:4px;font-weight:700;font-size:13px;color:#475569">API Key</label>
            <input :value="store.aiConfig?.apiKeyMasked || '(未配置)'" disabled placeholder="通过环境变量 AI_API_KEY 配置" />
            <p class="hint">API Key 通过环境变量 <code>AI_API_KEY</code> 注入，不可在此修改</p>
          </div>
          <div>
            <label style="display:block;margin-bottom:4px;font-weight:700;font-size:13px;color:#475569">备注</label>
            <textarea v-model="form.remark" placeholder="可选备注信息" style="min-height:60px"></textarea>
          </div>
          <div>
            <button class="primary" :disabled="store.aiConfigLoading">保存配置</button>
          </div>
        </form>
      </div>
    </section>

    <!-- 启用/关闭 -->
    <section v-if="store.aiConfig" class="form-card" style="margin-top:16px">
      <h2>服务状态</h2>
      <div style="display:flex;align-items:center;gap:16px;margin-top:12px">
        <span :class="['tag', store.aiConfig.enabled ? 'blue' : '']">{{ store.aiConfig.enabled ? '已启用' : '已关闭' }}</span>
        <button :disabled="store.aiConfigLoading" @click="toggleEnabled">
          {{ store.aiConfig.enabled ? '关闭 AI 服务' : '启用 AI 服务' }}
        </button>
        <span v-if="store.aiConfigLoading" class="muted">操作中...</span>
      </div>
    </section>

    <!-- 测试 -->
    <section class="form-card" style="margin-top:16px">
      <h2>测试连接</h2>
      <div style="display:flex;align-items:center;gap:12px;margin-top:12px">
        <button :disabled="store.aiConfigLoading" @click="runTest">发送测试请求</button>
        <span v-if="store.aiTestResult" :class="store.aiTestResult.includes('正常') ? 'tag blue' : 'tag danger'">{{ store.aiTestResult }}</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.tag { display:inline-block;padding:2px 10px;border-radius:12px;font-size:12px;font-weight:700;background:#edf2f7;color:#4a5568; }
.tag.blue { background:#ebf8ff;color:#2b6cb0; }
.tag.danger { background:#fff5f5;color:#c53030; }
.form-card { background:#fff;border:1px solid #e5eaf2;border-radius:8px;padding:18px; }
.create-panel h2 { margin-bottom:12px; }
.hint { margin-top:6px;font-size:12px;color:#718096; }
code { background:#edf2f7;padding:1px 6px;border-radius:4px;font-size:12px; }
</style>
