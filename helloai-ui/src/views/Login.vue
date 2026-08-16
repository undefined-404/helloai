<template>
  <div class="login-page">
    <div class="login-brand">
      <StarfieldBackground />
      <div class="brand-hero">
        <div class="brand-header">
          <div class="brand-text-group">
            <span class="brand-header-text">Hello</span>
            <span class="brand-header-text brand-header-accent"> AI</span>
          </div>
        </div>

        <div class="character-area">
          <AnimatedCharacter
            :size="320"
            :is-focused="isFormFocused"
            :is-typing="isTyping"
            :focus-target="focusTarget"
            primary-color="#7C3AED"
          />
        </div>
      </div>

      <div class="brand-bottom">
        <div class="brand-quote">
          <p class="quote-text">
            编排、监控、优化 AI Agent 工作流
          </p>
          <p class="quote-sub">
            让智能体协作更高效
          </p>
        </div>

        <div class="panel-footer">
          <a href="#">服务条款</a>
          <a href="#">隐私政策</a>
          <span class="footer-version">v1.0</span>
        </div>
      </div>

      <div class="deco-grid" />
      <div class="deco-blur deco-blur-1" />
      <div class="deco-blur deco-blur-2" />
    </div>

    <div class="login-panel">
      <div class="login-card-wrapper">
        <div class="mobile-logo">
          <span class="mobile-logo-text">Hello</span>
          <span class="mobile-logo-text mobile-logo-accent">AI</span>
        </div>

        <div class="login-card">
          <div class="login-card-header">
            <h2 class="login-heading">
              {{ pageTitle }}
            </h2>
            <p class="login-hint">
              {{ pageHint }}
            </p>
          </div>

          <EntryTabs
            v-if="entryMode === null"
            @select="setEntryMode"
          />

          <LoginForm
            v-else-if="entryMode === 'login'"
            @back="resetEntryMode"
            @success="handleLoginSuccess"
            @focus="onFocus"
            @blur="onBlur"
            @input="onInput"
            @password-support="(username) => openSupport('password', username)"
            @contact-support="openSupport('contact')"
          />

          <RegisterStack
            v-else
            :setup-status="setupStatus"
            @back="resetEntryMode"
            @goto-setup="goToSetup"
            @contact="openSupport('contact')"
          />
        </div>

        <p class="login-footer">
          Hello AI - AI Agent Management Platform
        </p>
      </div>
    </div>

    <SupportDialog
      v-model="supportDialogVisible"
      :kind="supportKind"
      :content="supportContent"
      :show-goto-setup="supportKind === 'password' && (!setupStatus.setupFinished || !setupStatus.hasUsers)"
      @copy-template="copySupportTemplate"
      @goto-setup="goToSetup"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { settingsApi } from '@/api/settings'
import { useAuthStore } from '@/stores/auth'
import { copyTextWithToast } from '@/composables/useClipboardWithFallback'
import AnimatedCharacter from '@/components/AnimatedCharacter.vue'
import StarfieldBackground from '@/components/StarfieldBackground.vue'
import EntryTabs from './login/EntryTabs.vue'
import type { EntryMode } from './login/EntryTabs.vue'
import LoginForm from './login/LoginForm.vue'
import RegisterStack from './login/RegisterStack.vue'
import type { SetupStatus } from './login/RegisterStack.vue'
import SupportDialog from './login/SupportDialog.vue'
import type { SupportKind, SupportContent } from './login/SupportDialog.vue'

const router = useRouter()

const entryMode = ref<EntryMode | null>(null)
const supportKind = ref<SupportKind>('password')
const supportDialogVisible = ref(false)
// 找回密码模板需要把当前用户名注入占位，登录表单内部持有，emit 时回传
const passwordSupportUsername = ref('')

const isFormFocused = ref(false)
const isTyping = ref(false)
const focusTarget = ref<'none' | 'email' | 'password'>('none')

let typingTimer: ReturnType<typeof setTimeout> | null = null

const setupStatus = reactive<SetupStatus>({
  loading: true,
  setupFinished: true,
  hasUsers: true,
  userCount: 1
})

const pageTitle = computed(() => {
  if (entryMode.value === 'login') return '欢迎回来'
  if (entryMode.value === 'register') return '注册与开通'
  return '欢迎回来'
})

const pageHint = computed(() => {
  if (entryMode.value === 'login') return '使用管理员账号与密码登录。'
  if (entryMode.value === 'register') return '账号由平台管理员统一开通，或首次部署时前往初始化。'
  return '先选择访问入口，再进入对应流程。'
})

const supportContent = computed<SupportContent>(() => {
  if (supportKind.value === 'password') {
    if (!setupStatus.setupFinished || !setupStatus.hasUsers) {
      return {
        title: '找回密码',
        intro: '当前环境还没有完成初始化，先创建首个管理员账号更符合真实场景。',
        steps: [
          '前往初始化向导，创建首个管理员账号。',
          '完成初始化后，使用新创建的账号密码登录。',
          '登录成功后，可在右下角用户菜单里修改密码。'
        ],
        template: ''
      }
    }

    return {
      title: '找回密码',
      intro: '当前版本的密码找回由平台管理员处理，不再让你在两个登录入口之间反复试错。',
      steps: [
        '联系平台管理员重置你的管理员密码。',
        '拿到新密码后，回到“账号密码登录”完成登录。',
        '登录后立即在用户菜单中修改为你的个人密码。'
      ],
      template: `你好，我需要重置 Hello AI 管理员密码。\n用户名：${passwordSupportUsername.value || '[请填写用户名]'}\n原因：无法使用当前密码登录\n期望时间：${new Date().toLocaleString()}`
    }
  }

  return {
    title: '联系管理员',
    intro: '当前版本还没有配置统一客服通道，先给你一份可直接转发的联系模板。',
    steps: [
      '复制下面模板，通过企业微信、邮件或内部 IM 发送给平台管理员。',
      '说明你的身份、访问场景，以及需要账号还是重置密码。',
      '收到反馈后，再回到登录页使用账号密码登录。'
    ],
    template: `你好，我需要处理 Hello AI 访问权限。\n申请类型：开通账号 / 重置密码\n身份说明：[请填写]\n使用场景：[请填写]\n联系方式：[请填写]`
  }
})

function setEntryMode(mode: EntryMode) {
  entryMode.value = mode
  onBlur()
}

function resetEntryMode() {
  entryMode.value = null
  onBlur()
}

function goToSetup() {
  supportDialogVisible.value = false
  router.push('/setup')
}

function openSupport(kind: SupportKind, username = '') {
  supportKind.value = kind
  if (kind === 'password') passwordSupportUsername.value = username
  supportDialogVisible.value = true
}

function onFocus(target: 'email' | 'password') {
  isFormFocused.value = true
  focusTarget.value = target
}

function onBlur() {
  isFormFocused.value = false
  focusTarget.value = 'none'
}

function onInput() {
  isTyping.value = true
  if (typingTimer) clearTimeout(typingTimer)
  typingTimer = setTimeout(() => {
    isTyping.value = false
  }, 800)
}

async function copySupportTemplate() {
  if (!supportContent.value.template) return
  // 走统一 composable：Clipboard API + execCommand 双路径降级
  await copyTextWithToast(supportContent.value.template, '联系模板已复制', '复制失败，请手动复制')
}

async function loadSetupStatus() {
  try {
    const status = await settingsApi.getStatus()
    setupStatus.setupFinished = status.setupFinished
    setupStatus.hasUsers = status.hasUsers
    setupStatus.userCount = status.userCount
  } catch {
    // 保持保守默认值，避免把已初始化系统误判成首次部署
  } finally {
    setupStatus.loading = false
  }
}

function handleLoginSuccess() {
  router.push('/dashboard')
}

onMounted(() => {
  // 单一来源：store 已是 sessionStorage 的镜像，从 store 读取
  const auth = useAuthStore()
  if (auth.isAdmin) {
    router.replace('/dashboard')
    return
  }
  if (auth.isAgent) {
    router.replace('/inbox')
    return
  }
  loadSetupStatus()
})

onBeforeUnmount(() => {
  if (typingTimer) clearTimeout(typingTimer)
})
</script>

<style scoped>
.login-page {
  display: flex;
  height: 100vh;
  overflow: hidden;
  --login-accent-start: #A78BFA;
  --login-accent-solid: #7C3AED;
  --login-accent-solid-hover: #6D28D9;
  --login-accent-solid-active: #5B21B6;
  --login-accent-soft: rgba(124, 58, 237, 0.12);
  --login-accent-soft-hover: rgba(124, 58, 237, 0.20);
  --login-accent-border: rgba(124, 58, 237, 0.35);
  --login-accent-border-strong: rgba(167, 139, 250, 0.65);
  --login-accent-ink: #C4B5FD;
  --login-accent-shadow: 0 10px 24px rgba(124, 58, 237, 0.35);
  --login-accent-shadow-hover: 0 14px 30px rgba(124, 58, 237, 0.45);

  /* 登录页专用暗色主题：仅覆盖本页作用域内的 --ha-* token，不影响登录后后台 */
  --ha-bg: #0A0E1A;
  --ha-surface: rgba(255, 255, 255, 0.03);
  --ha-surface-elevated: rgba(18, 24, 40, 0.60);
  --ha-border: rgba(255, 255, 255, 0.10);
  --ha-border-light: rgba(255, 255, 255, 0.08);
  --ha-ink: #EEF2F8;
  --ha-ink-secondary: rgba(255, 255, 255, 0.72);
  --ha-muted: rgba(255, 255, 255, 0.50);
  --ha-primary-muted: rgba(124, 58, 237, 0.18);
}

.login-brand {
  flex: 1;
  background: hsl(217, 64%, 6%);
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  padding: 2rem 3rem;
  position: relative;
  overflow: hidden;
  isolation: isolate;
}

.brand-hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  position: relative;
  z-index: 2;
}

.brand-header {
  text-align: center;
  margin-bottom: 16px;
}

.brand-text-group {
  display: flex;
  gap: 4px;
  justify-content: center;
}

.brand-header-text {
  color: #fff;
  font-size: 80px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1;
  /* 轻微发光提升科技感：紫色为主与主色系一致，外圈极淡青色呼应右下光斑 */
  text-shadow: 0 0 20px rgba(167, 139, 250, 0.30),
               0 0 64px rgba(6, 182, 212, 0.12);
}

.brand-header-accent {
  font-weight: 800;
}

.character-area {
  display: flex;
  justify-content: center;
  margin-top: 8px;
  filter: drop-shadow(0 16px 48px rgba(0, 0, 0, 0.25));
}

.brand-bottom {
  position: relative;
  z-index: 2;
}

.brand-quote {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  margin-bottom: 32px;
}

.quote-text {
  color: rgba(255, 255, 255, 0.85);
  font-size: 18px;
  font-weight: 500;
  margin: 0 0 8px;
  max-width: none;
  text-wrap: balance;
}

.quote-sub {
  color: rgba(255, 255, 255, 0.55);
  font-size: 14px;
  margin: 0;
  max-width: none;
}

.panel-footer {
  display: flex;
  justify-content: center;
  gap: 24px;
  padding-bottom: 8px;
}

.panel-footer a {
  color: rgba(255, 255, 255, 0.40);
  font-size: 11px;
  text-decoration: none;
  transition: color var(--ha-duration-fast);
}

.panel-footer a:hover {
  color: rgba(255, 255, 255, 0.75);
}

.footer-version {
  color: rgba(255, 255, 255, 0.28);
  font-size: 11px;
}

.deco-grid {
  position: absolute;
  inset: 0;
  z-index: 1;
  background-image:
    linear-gradient(to right, rgba(255, 255, 255, 0.03) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
  background-size: 32px 32px;
  pointer-events: none;
}

.deco-blur {
  position: absolute;
  z-index: 1;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  will-change: transform, filter;
}

.deco-blur-1 {
  top: 45%;
  left: 50%;
  width: 420px;
  height: 420px;
  margin-top: -210px;
  margin-left: -210px;
  background: rgba(124, 58, 237, 0.18);
  animation: blur-float-1 14s ease-in-out infinite;
}

.deco-blur-2 {
  top: 58%;
  left: 50%;
  width: 460px;
  height: 460px;
  margin-top: -230px;
  margin-left: -230px;
  background: rgba(6, 182, 212, 0.10);
  animation: blur-float-2 18s ease-in-out infinite;
}

@keyframes blur-float-1 {
  0% { transform: translate3d(0, 0, 0) scale(1); }
  50% { transform: translate3d(-70px, 40px, 0) scale(1.08); }
  100% { transform: translate3d(0, 0, 0) scale(1); }
}

@keyframes blur-float-2 {
  0% { transform: translate3d(0, 0, 0) scale(1); }
  50% { transform: translate3d(80px, -50px, 0) scale(1.06); }
  100% { transform: translate3d(0, 0, 0) scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .deco-blur-1,
  .deco-blur-2 {
    animation: none;
  }
}

.login-panel {
  width: 520px;
  background: var(--ha-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.login-card-wrapper {
  width: 100%;
  max-width: 420px;
}

.mobile-logo {
  display: none;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 40px;
}

.mobile-logo-text {
  color: var(--ha-ink);
  letter-spacing: -0.01em;
}

.mobile-logo-accent {
  color: var(--ha-primary);
  font-weight: 800;
}

.login-card {
  position: relative;
  background: var(--ha-surface-elevated);
  backdrop-filter: blur(20px) saturate(1.2);
  -webkit-backdrop-filter: blur(20px) saturate(1.2);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--ha-radius-xl);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45),
              0 0 40px rgba(124, 58, 237, 0.08);
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.login-card-header {
  text-align: center;
}

.login-heading {
  font-size: 22px;
  font-weight: 600;
  color: var(--ha-ink);
  margin: 0 0 6px;
  letter-spacing: -0.02em;
  text-wrap: balance;
}

.login-hint {
  font-size: 14px;
  color: var(--ha-muted);
  margin: 0;
}

.login-footer {
  text-align: center;
  color: var(--ha-muted);
  font-size: 12px;
  margin-top: 24px;
}

@media (max-width: 1023px) {
  .login-page {
    flex-direction: column;
  }

  .login-brand {
    display: none;
  }

  .login-panel {
    width: 100%;
    min-height: 100dvh;
    padding: 24px 16px;
  }

  .login-card-wrapper {
    max-width: 100%;
  }

  .mobile-logo {
    display: flex;
  }
}
</style>
