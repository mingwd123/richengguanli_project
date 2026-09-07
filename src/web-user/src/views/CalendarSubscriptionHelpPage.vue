<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, BookOpen, Copy, MonitorSmartphone } from 'lucide-vue-next'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()

onMounted(async () => {
  try {
    await store.loadSubscribeToken()
  } catch {
    // 订阅信息加载失败不阻断文档页
  }
})

function goBack() {
  router.push('/profile/settings')
}

function fullIcalUrl(path?: string) {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path
  return `${location.origin}${path}`
}

async function copyIcalPath() {
  const text = fullIcalUrl(store.subscribeTokenInfo?.path)
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    store.notify('订阅链接已复制')
  } catch {
    store.notify('复制失败，请手动复制')
  }
}
</script>

<template>
  <section class="form-card profile-settings-card calendar-help-card">
    <header class="settings-page-head">
      <div>
        <p class="eyebrow">日历订阅</p>
        <h1>使用文档</h1>
      </div>
      <button type="button" title="返回设置" aria-label="返回设置" @click="goBack">
        <ArrowLeft :size="17" />
      </button>
    </header>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><MonitorSmartphone :size="18" /></span>
        <div>
          <h2>你的订阅链接</h2>
          <p>把它添加到任意支持 iCal 订阅的日历客户端</p>
        </div>
      </div>
      <div class="ical-subscription">
        <div class="subscription-path">
          <input type="text" :value="fullIcalUrl(store.subscribeTokenInfo?.path)" readonly placeholder="加载订阅链接..." />
          <button type="button" class="plain-button" :disabled="!store.subscribeTokenInfo?.path" @click="copyIcalPath">
            <Copy :size="14" /> 复制
          </button>
        </div>
        <p class="hint">链接包含个人 Token，等同于你的账号凭证，请勿分享给他人。</p>
      </div>
    </section>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><BookOpen :size="18" /></span>
        <div>
          <h2>订阅方式</h2>
          <p>按你使用的日历客户端选择对应方法</p>
        </div>
      </div>
      <div class="help-steps">
        <div class="help-step">
          <span class="help-step-num">1</span>
          <div>
            <h3>Windows「日历」应用（推荐，本机使用）</h3>
            <ol>
              <li>打开开始菜单，搜索「日历」并打开应用。</li>
              <li>点击左侧栏底部的「管理账户」→「添加账户」。</li>
              <li>选择「从 Internet 订阅」。</li>
              <li>粘贴上面的订阅链接，命名后保存，日程会自动定期同步。</li>
            </ol>
          </div>
        </div>

        <div class="help-step">
          <span class="help-step-num">2</span>
          <div>
            <h3>Outlook</h3>
            <ol>
              <li>经典桌面版：文件 → 账户设置 → Internet 日历 → 新建，粘贴订阅链接。</li>
              <li>新版 Outlook / Microsoft 365 网页版：「从 Web 订阅」由微软云端拉取链接，<strong>无法访问本机地址（127.0.0.1）</strong>。服务部署到公网后即可使用，本地开发阶段请改用方式 1 或方式 5。</li>
            </ol>
          </div>
        </div>

        <div class="help-step">
          <span class="help-step-num">3</span>
          <div>
            <h3>Google 日历</h3>
            <ol>
              <li>打开 Google 日历，点击「其他日历」→「通过网址添加」。</li>
              <li>粘贴订阅链接，点击「添加日历」。</li>
              <li>注意：Google 服务器在公网拉取链接，同样需要服务部署在公网（HTTPS）后才能订阅成功。</li>
            </ol>
          </div>
        </div>

        <div class="help-step">
          <span class="help-step-num">4</span>
          <div>
            <h3>macOS / iPhone「日历」</h3>
            <ol>
              <li>macOS：打开日历 → 文件 → 新建日历订阅，粘贴链接。</li>
              <li>iPhone：设置 → 日历 → 账户 → 添加账户 → 其他 → 添加已订阅的日历。</li>
              <li>注意：手机需能访问到部署地址，本机开发地址（127.0.0.1）请改用局域网 IP 或公网域名。</li>
            </ol>
          </div>
        </div>

        <div class="help-step">
          <span class="help-step-num">5</span>
          <div>
            <h3>导出 .ics 文件导入（一次性同步）</h3>
            <ol>
              <li>复制订阅链接，粘贴到浏览器地址栏打开。</li>
              <li>页面会下载一个 .ics 文件（或用「另存为」保存）。</li>
              <li>在日历客户端选择「从文件导入 / 上传」。注意：此方式是当前时刻的快照，之后不会自动更新。</li>
            </ol>
          </div>
        </div>
      </div>
    </section>

    <section class="settings-section">
      <div class="settings-section-head">
        <span><BookOpen :size="18" /></span>
        <div>
          <h2>常见问题</h2>
          <p>订阅失败时先看这里</p>
        </div>
      </div>
      <div class="help-faq">
        <p><strong>订阅后日历是空的？</strong></p>
        <p class="hint">订阅内容只包含「待办」状态的个人日程和分配给你且未完成的团队任务。已完成、已取消的任务不会出现在订阅日历中。</p>
        <p><strong>提示「无法导入日历」？</strong></p>
        <p class="hint">多数情况是客户端（尤其是云端拉取的新版 Outlook、Google 日历）访问不到订阅地址。本地开发阶段（127.0.0.1）请使用 Windows 日历或 .ics 文件导入。</p>
        <p><strong>重置 Token 后原来的订阅失效了？</strong></p>
        <p class="hint">重置后旧链接立即失效，属于正常的安全机制。到日历客户端删除旧订阅，用新链接重新订阅即可。</p>
        <p><strong>时区显示不对？</strong></p>
        <p class="hint">订阅内容按你的账号时区生成。如果修改了账号时区，删除订阅后重新添加即可刷新。</p>
      </div>
    </section>
  </section>
</template>
