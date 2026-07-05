<template>
  <div class="login-page">
    <div class="login-brand">
      <div class="brand-hero">
        <div class="brand-header">
          <div class="brand-text-group">
            <span class="brand-header-text">Hello</span>
            <span class="brand-header-text brand-header-accent"> AI</span>
          </div>
        </div>

        <div class="character-area">
          <AnimatedCharacter
            :size="300"
            :isFocused="isFormFocused"
            :isTyping="isTyping"
            :focusTarget="focusTarget"
            primaryColor="#7C3AED"
          />
        </div>
      </div>

      <div class="brand-bottom">
        <div class="brand-quote">
          <p class="quote-text">编排、监控、优化 AI Agent 工作流</p>
          <p class="quote-sub">让智能体协作更高效</p>
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
            <h2 class="login-heading">{{ pageTitle }}</h2>
            <p class="login-hint">{{ pageHint }}</p>
          </div>

          <template v-if="entryMode === null">
            <div class="entry-tabs" role="tablist" aria-label="访问入口">
              <button
                type="button"
                role="tab"
                class="entry-tab"
                @click="setEntryMode('login')"
              >
                登录
              </button>
              <button
                type="button"
                role="tab"
                class="entry-tab"
                @click="setEntryMode('register')"
              >
                注册
              </button>
            </div>
          </template>

          <template v-else-if="entryMode === 'login'">
            <div class="subview-actions">
              <button type="button" class="helper-link helper-link-quiet" @click="resetEntryMode">
                返回选择
              </button>
            </div>
            <el-form :model="form" label-width="0" size="large" class="login-form" @keyup.enter="handleLogin">
              <div class="section-stack">
                <div class="login-tabs" role="tablist" aria-label="登录方式">
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="form.type === 'admin'"
                    :class="['login-tab', { active: form.type === 'admin' }]"
                    @click="setLoginMode('admin')"
                  >
                    账号密码登录
                  </button>
                  <button
                    type="button"
                    role="tab"
                    :aria-selected="form.type === 'agent'"
                    :class="['login-tab', { active: form.type === 'agent' }]"
                    @click="setLoginMode('agent')"
                  >
                    API Key 登录
                  </button>
                </div>

                <div class="mode-context">
                  <p class="mode-context-title">{{ modeTitle }}</p>
                  <p class="mode-context-desc">{{ modeDescription }}</p>
                </div>

                <el-form-item v-if="form.type === 'admin'">
                  <el-input
                    v-model="form.username"
                    placeholder="管理员用户名"
                    :prefix-icon="User"
                    clearable
                    @focus="onFocus('email')"
                    @blur="onBlur"
                    @input="onInput"
                  />
                </el-form-item>

                <el-form-item>
                  <div class="password-field">
                    <el-input
                      v-model="form.credential"
                      :placeholder="form.type === 'admin' ? '登录密码' : 'Agent API Key'"
                      :type="showPassword && form.type === 'admin' ? 'text' : 'password'"
                      :prefix-icon="form.type === 'admin' ? Lock : Key"
                      clearable
                      @focus="onFocus('password')"
                      @blur="onBlur"
                      @input="onInput"
                    />
                    <button
                      v-if="form.credential && form.type === 'admin'"
                      type="button"
                      class="toggle-pwd-btn"
                      :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                      @click="showPassword = !showPassword"
                    >
                      <el-icon :size="18"><View /></el-icon>
                    </button>
                  </div>
                </el-form-item>

                <div class="helper-links">
                  <button
                    v-if="form.type === 'admin'"
                    type="button"
                    class="helper-link"
                    @click="openSupport('password')"
                  >
                    找回密码
                  </button>
                  <button
                    v-else
                    type="button"
                    class="helper-link"
                    @click="openSupport('apiKey')"
                  >
                    获取 API Key
                  </button>
                  <button type="button" class="helper-link" @click="openSupport('contact')">
                    联系管理员
                  </button>
                </div>

                <el-button type="primary" :loading="loading" class="login-submit" @click="handleLogin">
                  {{ submitLabel }}
                </el-button>

                <transition name="msg-fade">
                  <p v-if="errorMsg" class="login-error">{{ errorMsg }}</p>
                </transition>
              </div>
            </el-form>
          </template>

          <div v-else class="register-stack">
            <div class="subview-actions">
              <button type="button" class="helper-link helper-link-quiet" @click="resetEntryMode">
                返回选择
              </button>
            </div>

            <div class="register-card">
              <div class="register-card-header">
                <div>
                  <p class="register-card-title">管理员账号</p>
                  <p class="register-card-desc">
                    {{
                      setupStatus.setupFinished && setupStatus.hasUsers
                        ? '管理员账号由平台管理员统一开通。'
                        : '当前环境未初始化，可先创建管理员账号。'
                    }}
                  </p>
                </div>
                <span class="register-badge">
                  {{ setupStatus.loading ? '检查中' : setupStatus.setupFinished && setupStatus.hasUsers ? '已初始化' : '首次部署' }}
                </span>
              </div>

              <div class="register-card-actions">
                <el-button
                  v-if="!setupStatus.setupFinished || !setupStatus.hasUsers"
                  type="primary"
                  @click="router.push('/setup')"
                >
                  前往初始化
                </el-button>
                <el-button
                  v-else
                  type="primary"
                  plain
                  @click="openSupport('contact')"
                >
                  联系管理员开通
                </el-button>
                <el-button text @click="goToLogin('admin')">已有账号</el-button>
              </div>
            </div>

            <div class="register-card">
              <div class="register-card-header">
                <div>
                  <p class="register-card-title">Agent API Key</p>
                  <p class="register-card-desc">
                    由管理员分配，用于 Agent 接入与调试。
                  </p>
                </div>
                <span class="register-badge">Agent</span>
              </div>

              <div class="register-card-actions">
                <el-button type="primary" plain @click="openSupport('apiKey')">获取 API Key</el-button>
                <el-button text @click="goToLogin('agent')">已有 API Key</el-button>
              </div>
            </div>

            <p class="register-tip">
              没有统一自助注册接口时，不再让“管理员登录”和“API Key 登录”彼此混淆，而是先区分“我要登录”还是“我要开通访问”。
            </p>
          </div>
        </div>

        <p class="login-footer">Hello AI - AI Agent Management Platform</p>
      </div>
    </div>

    <el-dialog
      v-model="supportDialogVisible"
      :title="supportContent.title"
      width="440px"
      class="support-dialog"
      append-to-body
      destroy-on-close
    >
      <p class="support-dialog-intro">{{ supportContent.intro }}</p>
      <ol class="support-dialog-list">
        <li v-for="item in supportContent.steps" :key="item">{{ item }}</li>
      </ol>

      <div v-if="supportContent.template" class="support-template">
        <div class="support-template-header">
          <span>联系模板</span>
          <button type="button" class="helper-link" @click="copySupportTemplate">复制模板</button>
        </div>
        <pre>{{ supportContent.template }}</pre>
      </div>

      <template #footer>
        <div class="support-dialog-footer">
          <el-button
            v-if="supportKind === 'password' && (!setupStatus.setupFinished || !setupStatus.hasUsers)"
            type="primary"
            plain
            @click="goToSetup"
          >
            前往初始化
          </el-button>
          <el-button @click="supportDialogVisible = false">知道了</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Key, Lock, User, View } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { settingsApi } from '@/api/settings'
import AnimatedCharacter from '@/components/AnimatedCharacter.vue'

type EntryMode = 'login' | 'register'
type LoginMode = 'admin' | 'agent'
type SupportKind = 'password' | 'contact' | 'apiKey'

const router = useRouter()
const loading = ref(false)
const errorMsg = ref('')
const showPassword = ref(false)
const entryMode = ref<EntryMode | null>(null)
const supportKind = ref<SupportKind>('password')
const supportDialogVisible = ref(false)

const isFormFocused = ref(false)
const isTyping = ref(false)
const focusTarget = ref<'none' | 'email' | 'password'>('none')

let typingTimer: ReturnType<typeof setTimeout> | null = null

const setupStatus = reactive({
  loading: true,
  setupFinished: true,
  hasUsers: true,
  userCount: 1
})

const form = reactive({
  type: 'admin' as LoginMode,
  username: '',
  credential: ''
})

const pageTitle = computed(() => {
  if (entryMode.value === 'login') return '欢迎回来'
  if (entryMode.value === 'register') return '注册与开通'
  return '欢迎回来'
})
const pageHint = computed(() => {
  if (entryMode.value === 'login') return '先选择登录方式，再输入对应凭证。'
  if (entryMode.value === 'register') return '先区分开通场景，再进入对应的登录方式。'
  return '先选择访问入口，再进入对应流程。'
})
const modeTitle = computed(() => form.type === 'admin' ? '账号密码登录' : 'API Key 登录')
const modeDescription = computed(() => (
  form.type === 'admin'
    ? '适用于平台管理员处理任务、规则配置、审查与系统设置。'
    : '适用于已注册 Agent 接入平台、查看收件箱和回传执行结果。'
))
const submitLabel = computed(() => form.type === 'admin' ? '使用账号密码登录' : '使用 API Key 登录')

const supportContent = computed(() => {
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
      template: `你好，我需要重置 Hello AI 管理员密码。\n用户名：${form.username || '[请填写用户名]'}\n原因：无法使用当前密码登录\n期望时间：${new Date().toLocaleString()}`
    }
  }

  if (supportKind.value === 'apiKey') {
    return {
      title: '获取 API Key',
      intro: 'API Key 适用于 Agent 接入，不应该和管理员密码登录并列成同一认知层级。',
      steps: [
        '联系平台管理员，在“Agent 管理”中创建或确认你的 Agent。',
        '由管理员复制该 Agent 的 API Key 给你。',
        '回到登录页，切换到“API Key 登录”后粘贴使用。'
      ],
      template: `你好，我需要申请 Hello AI Agent API Key。\nAgent 名称：${form.username || '[请填写 Agent 名称]'}\n用途：接入平台 / 调试 / 收件箱处理\n期望开通时间：${new Date().toLocaleString()}`
    }
  }

  return {
    title: '联系管理员',
    intro: '当前版本还没有配置统一客服通道，先给你一份可直接转发的联系模板。',
    steps: [
      '复制下面模板，通过企业微信、邮件或内部 IM 发送给平台管理员。',
      '说明你的身份、访问场景，以及需要账号、重置密码还是 API Key。',
      '收到反馈后，再回到对应入口登录。'
    ],
    template: `你好，我需要处理 Hello AI 访问权限。\n申请类型：${form.type === 'agent' ? '申请 API Key' : '开通账号 / 重置密码'}\n身份说明：[请填写]\n使用场景：[请填写]\n联系方式：[请填写]`
  }
})

function clearAuthStorage() {
  sessionStorage.removeItem('adminToken')
  sessionStorage.removeItem('adminUser')
  sessionStorage.removeItem('agentKey')
  sessionStorage.removeItem('agentName')
  sessionStorage.removeItem('loginType')
}

function setEntryMode(mode: EntryMode) {
  entryMode.value = mode
  errorMsg.value = ''
  showPassword.value = false
  onBlur()
}

function resetEntryMode() {
  entryMode.value = null
  errorMsg.value = ''
  showPassword.value = false
  onBlur()
}

function setLoginMode(mode: LoginMode) {
  form.type = mode
  form.credential = ''
  errorMsg.value = ''
  showPassword.value = false
  onBlur()
}

function goToLogin(mode: LoginMode) {
  setEntryMode('login')
  setLoginMode(mode)
}

function goToSetup() {
  supportDialogVisible.value = false
  router.push('/setup')
}

function openSupport(kind: SupportKind) {
  supportKind.value = kind
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
  errorMsg.value = ''
  isTyping.value = true
  if (typingTimer) clearTimeout(typingTimer)
  typingTimer = setTimeout(() => {
    isTyping.value = false
  }, 800)
}

async function copySupportTemplate() {
  if (!supportContent.value.template) return
  try {
    await navigator.clipboard.writeText(supportContent.value.template)
    ElMessage.success('联系模板已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
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

async function handleLogin() {
  const username = form.username.trim()
  const credential = form.credential.trim()

  if (form.type === 'admin' && !username) {
    errorMsg.value = '请输入管理员用户名'
    return
  }

  if (!credential) {
    errorMsg.value = form.type === 'admin' ? '请输入登录密码' : '请输入 Agent API Key'
    return
  }

  loading.value = true
  errorMsg.value = ''

  try {
    const res = await authApi.login({
      type: form.type,
      username: form.type === 'admin' ? username : undefined,
      credential
    })

    clearAuthStorage()
    sessionStorage.setItem('loginType', res.type)

    if (res.type === 'admin') {
      sessionStorage.setItem('adminToken', res.token)
      sessionStorage.setItem('adminUser', res.displayName || username || 'Admin')
      ElMessage.success('登录成功')
      router.push('/dashboard')
      return
    }

    sessionStorage.setItem('agentKey', res.token)
    sessionStorage.setItem('agentName', res.displayName || 'Agent')
    ElMessage.success('API Key 验证通过')
    router.push('/inbox')
  } catch (e: any) {
    errorMsg.value = e?.message || '登录失败，请检查账号或凭证'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (sessionStorage.getItem('adminToken')) {
    router.replace('/dashboard')
    return
  }
  if (sessionStorage.getItem('agentKey')) {
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
  --login-accent-start: #7C3AED;
  --login-accent-solid: #7C3AED;
  --login-accent-solid-hover: #6D28D9;
  --login-accent-solid-active: #5B21B6;
  --login-accent-soft: #F4F1FF;
  --login-accent-soft-hover: #EEE8FF;
  --login-accent-border: #D8CCFF;
  --login-accent-border-strong: #A78BFA;
  --login-accent-ink: #5B21B6;
  --login-accent-shadow: 0 10px 24px rgba(124, 58, 237, 0.20);
  --login-accent-shadow-hover: 0 14px 28px rgba(91, 33, 182, 0.24);
}

.login-brand {
  flex: 1;
  background: linear-gradient(135deg, #7C3AED 0%, #A78BFA 50%, #06B6D4 100%);
  background-size: 200% 200%;
  animation: brand-aurora 15s ease infinite;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  padding: 2rem 3rem;
  position: relative;
  isolation: isolate;
}

.brand-hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
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
}

.brand-header-accent {
  font-weight: 800;
}

.character-area {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

.brand-bottom {
  position: relative;
  z-index: 1;
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
  color: rgba(255, 255, 255, 0.55);
  font-size: 12px;
  text-decoration: none;
  transition: color var(--ha-duration-fast);
}

.panel-footer a:hover {
  color: rgba(255, 255, 255, 0.85);
}

.footer-version {
  color: rgba(255, 255, 255, 0.35);
  font-size: 12px;
}

.deco-grid {
  position: absolute;
  inset: 0;
  z-index: 0;
  background-image:
    linear-gradient(to right, rgba(255, 255, 255, 0.03) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
  background-size: 32px 32px;
  pointer-events: none;
}

.deco-blur {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  will-change: transform, filter;
}

.deco-blur-1 {
  top: 15%;
  right: 10%;
  width: 300px;
  height: 300px;
  background: rgba(124, 58, 237, 0.22);
  animation: blur-float-1 14s ease-in-out infinite;
}

.deco-blur-2 {
  bottom: 20%;
  left: 5%;
  width: 400px;
  height: 400px;
  background: rgba(6, 182, 212, 0.14);
  animation: blur-float-2 18s ease-in-out infinite;
}

@keyframes brand-aurora {
  0% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
  100% { background-position: 0% 50%; }
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
  .login-brand {
    animation: none;
    background-size: auto;
  }

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
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-xl);
  box-shadow: var(--ha-shadow-sm);
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

.entry-tabs,
.login-tabs {
  display: flex;
  gap: 8px;
  width: 100%;
}

.entry-tab,
.login-tab {
  flex: 1;
  height: 38px;
  border: 1px solid var(--login-accent-border);
  border-radius: var(--ha-radius-md);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96) 0%, var(--login-accent-soft) 100%);
  color: #6B5FA3;
  font-size: 13px;
  font-weight: 600;
  font-family: var(--ha-font-family);
  cursor: pointer;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.85);
  transition: all var(--ha-duration-fast) var(--ha-ease-out);
}

.entry-tab:hover,
.login-tab:hover {
  border-color: var(--login-accent-border-strong);
  background: linear-gradient(180deg, #FFFFFF 0%, var(--login-accent-soft-hover) 100%);
  color: var(--login-accent-ink);
  transform: translateY(-1px);
}

.entry-tab.active,
.login-tab.active {
  border-color: rgba(124, 58, 237, 0.08);
  background: var(--login-accent-solid);
  color: #FFFFFF;
  box-shadow: 0 8px 18px rgba(124, 58, 237, 0.18);
}

.section-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.subview-actions {
  display: flex;
  justify-content: flex-start;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.login-form :deep(.el-input__wrapper) {
  padding-left: 12px;
}

.mode-context {
  border: 1px solid var(--ha-border-light);
  background: var(--ha-surface);
  border-radius: var(--ha-radius-lg);
  padding: 14px 16px;
}

.mode-context-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-ink);
}

.mode-context-desc {
  margin: 0;
  font-size: 13px;
  color: var(--ha-muted);
  line-height: 1.6;
}

.password-field {
  position: relative;
  width: 100%;
}

.toggle-pwd-btn {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  color: var(--ha-muted);
  cursor: pointer;
  padding: 4px;
  z-index: 10;
  transition: color var(--ha-duration-fast);
}

.toggle-pwd-btn:hover {
  color: var(--ha-ink);
}

.helper-links {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.helper-link {
  border: none;
  background: none;
  padding: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--login-accent-ink);
  cursor: pointer;
  transition: color var(--ha-duration-fast) var(--ha-ease-out),
              opacity var(--ha-duration-fast) var(--ha-ease-out);
}

.helper-link:hover {
  color: var(--login-accent-start);
}

.helper-link-quiet {
  color: var(--ha-muted);
}

.helper-link-quiet:hover {
  color: var(--ha-ink);
}

.login-submit {
  width: 100%;
  height: 40px;
  font-size: 14px;
  font-weight: 600;
  --el-button-border-radius: var(--ha-radius-md);
  --el-button-text-color: #FFFFFF;
  --el-button-bg-color: transparent;
  --el-button-border-color: transparent;
  --el-button-hover-text-color: #FFFFFF;
  --el-button-hover-bg-color: transparent;
  --el-button-hover-border-color: transparent;
  --el-button-active-text-color: #FFFFFF;
  --el-button-active-bg-color: transparent;
  --el-button-active-border-color: transparent;
  background: var(--login-accent-solid);
  box-shadow: var(--login-accent-shadow);
  transition: all var(--ha-duration-normal) var(--ha-ease-out);
}

.login-submit:hover {
  transform: translateY(-1px);
  background: var(--login-accent-solid-hover);
  box-shadow: var(--login-accent-shadow-hover);
  filter: saturate(1.04);
}

.login-submit:active {
  transform: translateY(0);
  background: var(--login-accent-solid-active);
  box-shadow: 0 8px 18px rgba(124, 58, 237, 0.18);
}

.login-error {
  text-align: center;
  color: var(--ha-danger);
  font-size: 13px;
  margin: 0;
}

.register-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.register-card {
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-lg);
  background: var(--ha-bg);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.register-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.register-card-title {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ha-ink);
}

.register-card-desc {
  margin: 0;
  font-size: 13px;
  color: var(--ha-muted);
  line-height: 1.6;
}

.register-badge {
  flex-shrink: 0;
  border-radius: 999px;
  background: var(--ha-primary-muted);
  color: var(--ha-primary);
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 600;
}

.register-card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.register-card-actions :deep(.el-button) {
  white-space: nowrap;
}

.register-card-actions :deep(.el-button--primary) {
  --el-button-text-color: #FFFFFF;
  --el-button-bg-color: transparent;
  --el-button-border-color: transparent;
  --el-button-hover-text-color: #FFFFFF;
  --el-button-hover-bg-color: transparent;
  --el-button-hover-border-color: transparent;
  --el-button-active-text-color: #FFFFFF;
  --el-button-active-bg-color: transparent;
  --el-button-active-border-color: transparent;
  background: var(--login-accent-solid);
  box-shadow: 0 8px 18px rgba(124, 58, 237, 0.16);
}

.register-card-actions :deep(.el-button--primary:hover) {
  background: var(--login-accent-solid-hover);
  box-shadow: 0 12px 24px rgba(124, 58, 237, 0.20);
  filter: saturate(1.04);
}

.register-card-actions :deep(.el-button--primary:active) {
  background: var(--login-accent-solid-active);
}

.register-card-actions :deep(.el-button--primary.is-plain) {
  --el-button-text-color: var(--login-accent-ink);
  --el-button-hover-text-color: var(--login-accent-ink);
  --el-button-active-text-color: var(--login-accent-ink);
  background: linear-gradient(180deg, #FFFFFF 0%, var(--login-accent-soft) 100%);
  border: 1px solid var(--login-accent-border) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.register-card-actions :deep(.el-button--primary.is-plain:hover) {
  background: linear-gradient(180deg, #FFFFFF 0%, var(--login-accent-soft-hover) 100%);
  border-color: var(--login-accent-border-strong) !important;
  box-shadow: 0 8px 18px rgba(124, 58, 237, 0.10);
}

.register-card-actions :deep(.el-button--text) {
  --el-button-text-color: var(--login-accent-ink);
  --el-button-hover-text-color: var(--login-accent-start);
  --el-button-active-text-color: var(--login-accent-start);
  font-weight: 600;
}

.register-tip {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ha-muted);
}

.msg-fade-enter-active {
  animation: ha-fade-up 250ms var(--ha-ease-out) both;
}

.msg-fade-leave-active {
  animation: ha-fade-in 150ms var(--ha-ease-out) reverse both;
}

.login-footer {
  text-align: center;
  color: var(--ha-muted);
  font-size: 12px;
  margin-top: 24px;
}

.support-dialog-intro {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ha-muted);
}

.support-dialog-list {
  margin: 0;
  padding-left: 18px;
  color: var(--ha-ink-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.support-template {
  margin-top: 16px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-lg);
  background: var(--ha-surface);
  padding: 14px 16px;
}

.support-template-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-ink);
}

.support-template pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--ha-font-family);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ha-ink-secondary);
}

.support-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
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
