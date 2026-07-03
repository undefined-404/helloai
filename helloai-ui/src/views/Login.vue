<template>
  <div class="login-page">
    <!-- Left brand panel with animated character -->
    <div class="login-brand">
      <div class="brand-hero">
        <div class="brand-header">
          <div class="brand-text-group">
            <span class="brand-header-text">Hello</span>
            <span class="brand-header-text brand-header-accent">AI</span>
          </div>
        </div>

        <div class="character-area">
          <AnimatedCharacter
            :size="300"
            :isFocused="isFormFocused"
            :isTyping="isTyping"
            :focusTarget="focusTarget"
            :primaryColor="'#5B8DEF'"
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

      <!-- Decorative elements -->
      <div class="deco-grid" />
      <div class="deco-blur deco-blur-1" />
      <div class="deco-blur deco-blur-2" />
    </div>

    <!-- Right login panel -->
    <div class="login-panel">
      <div class="login-card-wrapper">
        <div class="mobile-logo">
      <span class="mobile-logo-text">Hello</span>
      <span class="mobile-logo-text mobile-logo-accent">AI</span>
    </div>

        <div class="login-card">
          <div class="login-card-header">
            <h2 class="login-heading">欢迎回来</h2>
            <p class="login-hint">请选择登录方式</p>
          </div>

          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-width="0"
            size="large"
            class="login-form"
            @keyup.enter="handleLogin"
          >
            <el-form-item prop="type">
              <div class="login-tabs">
                <button
                  :class="['login-tab', { active: form.type === 'admin' }]"
                  @click="form.type = 'admin'"
                >
                  管理员登录
                </button>
                <button
                  :class="['login-tab', { active: form.type === 'agent' }]"
                  @click="form.type = 'agent'"
                >
                  API Key 登录
                </button>
              </div>
            </el-form-item>

            <el-form-item v-if="form.type === 'admin'" prop="username">
              <el-input
                v-model="form.username"
                placeholder="用户名"
                :prefix-icon="User"
                clearable
                @focus="onFocus('email')"
                @blur="onBlur"
                @input="onTyping"
              />
            </el-form-item>

            <el-form-item prop="credential">
              <div class="password-field">
                <el-input
                  v-model="form.credential"
                  :placeholder="form.type === 'admin' ? '密码' : 'API Key'"
                  :type="showPassword ? 'text' : 'password'"
                  :prefix-icon="form.type === 'admin' ? Lock : Key"
                  clearable
                  @focus="onFocus('password')"
                  @blur="onBlur"
                  @input="onTyping"
                />
                <button
                  v-if="form.credential && form.type === 'admin'"
                  class="toggle-pwd-btn"
                  @click="showPassword = !showPassword"
                  type="button"
                >
                  <el-icon :size="18"><View /></el-icon>
                </button>
              </div>
            </el-form-item>

            <el-form-item>
              <el-button
                type="primary"
                :loading="loading"
                class="login-submit"
                @click="handleLogin"
              >
                登录
              </el-button>
            </el-form-item>

            <transition name="msg-fade">
              <p v-if="errorMsg" class="login-error">{{ errorMsg }}</p>
            </transition>
          </el-form>
        </div>

        <p class="login-footer">HelloAI &mdash; AI Agent Management Platform</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Key, View } from '@element-plus/icons-vue'
import request from '@/api/request'
import AnimatedCharacter from '@/components/AnimatedCharacter.vue'

interface LoginResponse {
  token: string
  type: 'admin' | 'agent'
  displayName?: string
  role?: string
}

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const errorMsg = ref('')
const showPassword = ref(false)

// Animated character state
const isFormFocused = ref(false)
const isTyping = ref(false)
const focusTarget = ref<'none' | 'email' | 'password'>('none')
let typingTimer: ReturnType<typeof setTimeout>

const form = reactive({
  type: 'admin',
  username: 'admin',
  credential: ''
})

const rules = {
  credential: [{ required: true, message: '请输入凭证', trigger: 'blur' }]
}

function onFocus(target: 'email' | 'password') {
  isFormFocused.value = true
  focusTarget.value = target
}

function onBlur() {
  isFormFocused.value = false
  isTyping.value = false
  focusTarget.value = 'none'
}

function onTyping() {
  isTyping.value = true
  clearTimeout(typingTimer)
  typingTimer = setTimeout(() => {
    isTyping.value = false
  }, 500)
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  errorMsg.value = ''
  try {
    const payload: any = {
      type: form.type,
      credential: form.credential
    }
    if (form.type === 'admin') {
      payload.username = form.username || 'admin'
    }
    const res = await request.post<any, LoginResponse>('/auth/login', payload)
    if (form.type === 'admin') {
      sessionStorage.setItem('adminToken', res.token)
      sessionStorage.setItem('adminUser', res.displayName || res.role || 'Admin')
      sessionStorage.removeItem('agentKey')
    } else {
      sessionStorage.setItem('agentKey', form.credential)
      sessionStorage.setItem('adminUser', res.displayName || 'Agent')
      sessionStorage.removeItem('adminToken')
    }
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (e: any) {
    errorMsg.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // Pre-focus state: character looks at visitor
  setTimeout(() => {
    isFormFocused.value = true
    focusTarget.value = 'email'
    setTimeout(() => {
      isFormFocused.value = false
      focusTarget.value = 'none'
    }, 2000)
  }, 1000)
})
</script>

<style scoped>
.login-page {
  min-height: 100dvh;
  display: flex;
  overflow: hidden;
}

/* =====================
   LEFT PANEL (Brand)
   ===================== */
.login-brand {
  flex: 1;
  background: linear-gradient(
    135deg,
    #7C3AED 0%,
    #A78BFA 50%,
    #06B6D4 100%
  );
  background-size: 400% 400%;
  animation: brand-aurora 15s ease infinite;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  padding: 2rem 3rem;
  position: relative;
  isolation: isolate;
  overflow: hidden;
  min-width: 0;
}

.brand-hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 100%;
  position: relative;
  z-index: 20;
}

.brand-bottom {
  display: flex;
  flex-direction: column;
  gap: 20px;
  position: relative;
  z-index: 20;
}

.brand-header {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  position: relative;
  margin-bottom: 30px;
}

.brand-icon-box {
  position: relative;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-icon-ring {
  position: absolute;
  inset: 0;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(43, 95, 217, 0.3), rgba(99, 102, 241, 0.15));
  box-shadow: 0 0 20px rgba(43, 95, 217, 0.15), inset 0 1px 0 rgba(255,255,255,0.1);
}
.brand-icon-inner {
  position: relative;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.95);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}

.brand-icon-img {
  width: 26px;
  height: 26px;
  display: block;
  object-fit: contain;
}

.brand-icon-img--mobile {
  width: 24px;
  height: 24px;
}

.brand-header-text {
  color: #fff;
  font-size: 80px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.brand-header-accent {
  color: #1E293B;
  font-weight: 800;
}
.brand-text-group {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

/* ---- Character Area ---- */
.character-area {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 300px;
}

/* ---- Quote ---- */
.brand-quote {
  position: relative;
  z-index: 20;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  width: 100%;
  margin: 0 auto;
  padding: 0 1rem;
}

.quote-text {
  color: rgba(255, 255, 255, 0.90);
  font-size: 16px;
  font-weight: 500;
  margin: 0 0 6px;
  text-wrap: balance;
}

.quote-sub {
  color: rgba(255, 255, 255, 0.55);
  font-size: 14px;
  margin: 0;
  text-wrap: pretty;
}

/* ---- Footer ---- */
.panel-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 24px;
  font-size: 13px;
  position: relative;
  z-index: 20;
}

.panel-footer a {
  color: rgba(255, 255, 255, 0.50);
  text-decoration: none;
  transition: color 0.15s;
}
.panel-footer a:hover {
  color: rgba(255, 255, 255, 0.80);
}

.footer-version {
  color: rgba(255, 255, 255, 0.25);
}

/* ---- Decorative ---- */
.deco-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, rgba(255,255,255,0.03) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255,255,255,0.03) 1px, transparent 1px);
  background-size: 32px 32px;
  pointer-events: none;
}

.deco-blur {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  will-change: transform;
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

/* =====================
   RIGHT PANEL (Form)
   ===================== */
.login-panel {
  width: 480px;
  background: var(--ha-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.login-card-wrapper {
  width: 380px;
}

/* ---- Mobile Logo ---- */
.mobile-logo {
  display: none;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 40px;
}

.brand-icon-box-mobile {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(43, 95, 217, 0.12), rgba(99, 102, 241, 0.06));
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 6px;
}

.mobile-logo-text {
  color: #1E293B;
  letter-spacing: -0.01em;
}
.mobile-logo-accent {
  color: #2B5FD9;
  font-weight: 800;
}

@media (max-width: 1023px) {
  .mobile-logo {
  display: none;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 40px;
}
}

/* ---- Card ---- */
.login-card {
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-xl);
  box-shadow: var(--ha-shadow-sm);
  padding: 32px;
}

.login-card-header {
  margin-bottom: 28px;
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

/* ---- Tabs ---- */
.login-tabs {
  display: flex;
  gap: 8px;
  width: 100%;
}

.login-tab {
  flex: 1;
  height: 38px;
  border: 1px solid var(--ha-border);
  border-radius: var(--ha-radius-md);
  background: transparent;
  color: var(--ha-muted);
  font-size: 13px;
  font-weight: 500;
  font-family: var(--ha-font-family);
  cursor: pointer;
  transition: all var(--ha-duration-fast) var(--ha-ease-out);
}

.login-tab:hover {
  border-color: #c4c8cf;
  color: var(--ha-ink);
}

.login-tab.active {
  border-color: var(--ha-primary);
  background: var(--ha-primary-muted);
  color: var(--ha-primary);
}

/* ---- Form ---- */
.login-form {
  margin-top: 0;
}

.login-form :deep(.el-input__wrapper) {
  padding-left: 12px;
}

.login-submit {
  width: 100%;
  height: 40px;
  font-size: 14px;
  font-weight: 600;
  --el-button-border-radius: var(--ha-radius-md);
  transition: all var(--ha-duration-normal) var(--ha-ease-out);
}

.login-submit:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 16px rgba(43, 95, 217, 0.30);
}

.login-submit:active {
  transform: translateY(0);
}

/* ---- Password Field ---- */
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
  transition: color 0.15s;
}
.toggle-pwd-btn:hover {
  color: var(--ha-ink);
}

/* ---- Error ---- */
.login-error {
  text-align: center;
  color: var(--ha-danger);
  font-size: 13px;
  margin: 0;
}

/* Error message transition */
.msg-fade-enter-active {
  animation: ha-fade-up 250ms var(--ha-ease-out) both;
}
.msg-fade-leave-active {
  animation: ha-fade-in 150ms ease reverse both;
}

/* ---- Footer ---- */
.login-footer {
  text-align: center;
  color: var(--ha-muted);
  font-size: 12px;
  margin-top: 24px;
}

/* =====================
   RESPONSIVE
   ===================== */
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
  }
}
</style>
