<template>
  <div class="register-stack">
    <div class="subview-actions">
      <button
        type="button"
        class="helper-link helper-link-quiet"
        @click="$emit('back')"
      >
        返回选择
      </button>
    </div>

    <!-- 自助注册开放（BASE-4.5）：渲染真实注册表单；注册账号默认为 GUEST（只读） -->
    <div
      v-if="setupStatus.registerEnabled"
      class="register-card"
    >
      <div class="register-card-header">
        <div>
          <p class="register-card-title">
            注册账号
          </p>
          <p class="register-card-desc">
            注册后为「游客」角色：可浏览业务数据，不可修改或创建。
          </p>
        </div>
        <span class="register-badge">开放注册</span>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="72px"
      >
        <el-form-item
          label="用户名"
          prop="username"
        >
          <el-input
            v-model="form.username"
            placeholder="2-64 位"
          />
        </el-form-item>
        <el-form-item
          label="密码"
          prop="password"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="6-64 位"
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input
            v-model="form.nickname"
            placeholder="昵称（可空）"
          />
        </el-form-item>
      </el-form>

      <div class="register-card-actions">
        <el-button
          type="primary"
          :loading="submitting"
          @click="submit"
        >
          注册
        </el-button>
        <el-button
          text
          @click="$emit('back')"
        >
          已有账号
        </el-button>
      </div>
    </div>

    <!-- 未开放自助注册：保留原有引导文案 -->
    <template v-else>
      <div class="register-card">
        <div class="register-card-header">
          <div>
            <p class="register-card-title">
              管理员账号
            </p>
            <p class="register-card-desc">
              {{ setupStatus.setupFinished && setupStatus.hasUsers
                ? '管理员账号由平台管理员统一开通。'
                : '当前环境未初始化，可先创建管理员账号。' }}
            </p>
          </div>
          <span class="register-badge">
            {{ setupStatus.loading
              ? '检查中'
              : setupStatus.setupFinished && setupStatus.hasUsers
                ? '已初始化'
                : '首次部署' }}
          </span>
        </div>

        <div class="register-card-actions">
          <el-button
            v-if="!setupStatus.setupFinished || !setupStatus.hasUsers"
            type="primary"
            @click="$emit('goto-setup')"
          >
            前往初始化
          </el-button>
          <el-button
            v-else
            type="primary"
            plain
            @click="$emit('contact')"
          >
            联系管理员开通
          </el-button>
          <el-button
            text
            @click="$emit('back')"
          >
            已有账号
          </el-button>
        </div>
      </div>

      <p class="register-tip">
        账号由平台管理员统一开通；首次部署时可通过「前往初始化」创建首个管理员账号。
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
// 注册视图（BASE-4.5）：
//   · 自助注册开启（setupStatus.registerEnabled）→ 渲染真实注册表单（注册账号默认 GUEST 只读）
//   · 未开启 → 保留引导文案（前往初始化 / 联系管理员开通）

import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'

export interface SetupStatus {
  loading: boolean
  setupFinished: boolean
  hasUsers: boolean
  userCount: number
  /** 是否开放自助注册（sys_config: auth.register.enabled） */
  registerEnabled: boolean
}

defineProps<{
  setupStatus: SetupStatus
}>()

const emit = defineEmits<{
  'back': []
  'goto-setup': []
  'contact': []
}>()

const formRef = ref()
const submitting = ref(false)
const form = ref({ username: '', password: '', nickname: '' })
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 64, message: '长度需在 2-64 位之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '长度需在 6-64 位之间', trigger: 'blur' }
  ]
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await authApi.register({
      username: form.value.username.trim(),
      password: form.value.password,
      nickname: form.value.nickname || undefined
    })
    ElMessage.success('注册成功，请使用新账号登录')
    emit('back')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.register-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.subview-actions {
  display: flex;
  justify-content: flex-start;
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
  color: var(--login-accent-ink);
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
  --el-button-hover-text-color: #FFFFFF;
  --el-button-active-text-color: #FFFFFF;
  background: rgba(124, 58, 237, 0.10);
  border: 1px solid var(--login-accent-border) !important;
  box-shadow: none;
}

.register-card-actions :deep(.el-button--primary.is-plain:hover) {
  background: rgba(124, 58, 237, 0.20);
  border-color: var(--login-accent-border-strong) !important;
  box-shadow: 0 8px 18px rgba(124, 58, 237, 0.20);
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
</style>
