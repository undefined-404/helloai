<template>
  <div class="login-form-block">
    <div class="subview-actions">
      <button
        type="button"
        class="helper-link helper-link-quiet"
        @click="$emit('back')"
      >
        返回选择
      </button>
    </div>

    <el-form
      :model="form"
      label-width="0"
      size="large"
      class="login-form"
      @keyup.enter="handleLogin"
    >
      <div class="section-stack">
        <div class="mode-context">
          <p class="mode-context-title">
            账号密码登录
          </p>
          <p class="mode-context-desc">
            适用于平台管理员处理任务、规则配置、审查与系统设置。
          </p>
        </div>

        <el-form-item>
          <el-input
            v-model="form.username"
            placeholder="管理员用户名"
            :prefix-icon="User"
            clearable
            @focus="$emit('focus', 'email')"
            @blur="$emit('blur')"
            @input="onInput"
          />
        </el-form-item>

        <el-form-item>
          <div class="password-field">
            <el-input
              v-model="form.credential"
              placeholder="登录密码"
              :type="showPassword ? 'text' : 'password'"
              :prefix-icon="Lock"
              clearable
              @focus="$emit('focus', 'password')"
              @blur="$emit('blur')"
              @input="onInput"
            />
            <button
              v-if="form.credential"
              type="button"
              class="toggle-pwd-btn"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <el-icon :size="18">
                <View />
              </el-icon>
            </button>
          </div>
        </el-form-item>

        <div class="helper-links">
          <button
            type="button"
            class="helper-link"
            @click="emitPasswordSupport"
          >
            找回密码
          </button>
          <button
            type="button"
            class="helper-link"
            @click="$emit('contact-support')"
          >
            联系管理员
          </button>
        </div>

        <el-button
          type="primary"
          :loading="loading"
          class="login-submit"
          @click="handleLogin"
        >
          使用账号密码登录
        </el-button>

        <transition name="msg-fade">
          <p
            v-if="errorMsg"
            class="login-error"
          >
            {{ errorMsg }}
          </p>
        </transition>
      </div>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Lock, User, View } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { msg } from '@/utils/messages'

const emit = defineEmits<{
  'back': []
  'success': []
  'focus': [target: 'email' | 'password']
  'blur': []
  'input': []
  'password-support': [username: string]
  'contact-support': []
}>()

const form = reactive({
  username: '',
  credential: ''
})

const loading = ref(false)
const errorMsg = ref('')
const showPassword = ref(false)

async function handleLogin() {
  const username = form.username.trim()
  const credential = form.credential.trim()

  if (!username) {
    errorMsg.value = msg.login.usernameRequired
    return
  }
  if (!credential) {
    errorMsg.value = msg.login.passwordRequired
    return
  }

  loading.value = true
  errorMsg.value = ''

  try {
    const res = await authApi.login({
      type: 'admin',
      username,
      credential
    })

    // 单一来源：store.setAdmin 会同步更新 sessionStorage 与 store 状态
    const auth = useAuthStore()
    auth.setAdmin(res.token, res.displayName || username || 'Admin')
    ElMessage.success(msg.login.success)
    emit('success')
  } catch (e: any) {
    errorMsg.value = e?.message || msg.login.failed
  } finally {
    loading.value = false
  }
}

function onInput() {
  // 父组件（Login.vue）需要 input 事件来驱动角色动画的 typing 状态；
  // 这里只透传，不做额外处理。
  emit('input')
}

function emitPasswordSupport() {
  // 找回密码模板需要用户名作为占位，所以从表单取并随事件传出去
  emit('password-support', form.username)
}
</script>

<style scoped>
.login-form-block {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.subview-actions {
  display: flex;
  justify-content: flex-start;
}

.section-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.login-form :deep(.el-input__wrapper) {
  padding-left: 12px;
}

/* Element Plus 输入框主题适配（继承父组件 .login-page 上的 --login-input-* token） */
.login-form :deep(.el-input__wrapper) {
  background-color: var(--login-input-bg);
  box-shadow: inset 0 0 0 1px var(--login-input-border);
}

.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: inset 0 0 0 1px var(--login-accent-border-strong);
}

.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: inset 0 0 0 1px var(--login-accent-solid),
              0 0 0 3px rgba(124, 58, 237, 0.22);
}

.login-form :deep(.el-input__inner) {
  color: var(--login-input-text);
  -webkit-text-fill-color: var(--login-input-text);
}

.login-form :deep(.el-input__inner::placeholder) {
  color: var(--login-input-placeholder);
}

.login-form :deep(.el-input__prefix),
.login-form :deep(.el-input__suffix),
.login-form :deep(.el-input__icon),
.login-form :deep(.el-input__clear) {
  color: var(--login-input-icon);
}

.login-form :deep(.el-input__inner:-webkit-autofill) {
  -webkit-text-fill-color: var(--login-input-text);
  transition: background-color 9999s ease-in-out 0s;
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
  /* 微透明玻璃态：半透紫 + blur + 浅紫细边框，保留主 CTA 权重 */
  background: rgba(124, 58, 237, 0.78);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(196, 181, 253, 0.35);
  box-shadow: var(--login-accent-shadow);
  transition: all var(--ha-duration-normal) var(--ha-ease-out);
}

.login-submit:hover {
  transform: translateY(-1px);
  background: rgba(109, 40, 217, 0.88);
  border-color: rgba(196, 181, 253, 0.50);
  box-shadow: var(--login-accent-shadow-hover);
  filter: saturate(1.04);
}

.login-submit:active {
  transform: translateY(0);
  background: rgba(91, 33, 182, 0.92);
  box-shadow: 0 8px 18px rgba(124, 58, 237, 0.18);
}

.login-error {
  text-align: center;
  color: var(--ha-danger);
  font-size: 13px;
  margin: 0;
}

.msg-fade-enter-active {
  animation: ha-fade-up 250ms var(--ha-ease-out) both;
}

.msg-fade-leave-active {
  animation: ha-fade-in 150ms var(--ha-ease-out) reverse both;
}
</style>
