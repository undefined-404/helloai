<template>
  <div class="setup-page">
    <div class="setup-card">
      <div class="setup-header">
        <el-icon
          :size="40"
          :color="'var(--ha-primary)'"
        >
          <MagicStick />
        </el-icon>
        <h2>HelloAI 初始化向导</h2>
        <p>首次启动，请配置平台基本信息</p>
      </div>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="110px"
        size="large"
      >
        <el-form-item
          label="管理员密码"
          prop="adminPassword"
        >
          <el-input
            v-model="form.adminPassword"
            type="password"
            show-password
            placeholder="至少6位"
          />
        </el-form-item>
        <el-form-item
          label="确认密码"
          prop="confirmPassword"
        >
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
          />
        </el-form-item>
        <el-form-item
          label="项目名称"
          prop="systemName"
        >
          <el-input
            v-model="form.systemName"
            placeholder="如 HelloAI"
          />
        </el-form-item>
        <el-form-item label="管理员账号">
          <el-input
            v-model="form.adminUsername"
            placeholder="默认 admin"
          />
        </el-form-item>
        <el-form-item label="项目描述">
          <el-input
            v-model="form.systemDescription"
            type="textarea"
            :rows="2"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="submitting"
            style="width:100%"
            @click="handleSubmit"
          >
            完成初始化
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { settingsApi } from '@/api/settings'

const router = useRouter()
const submitting = ref(false)
const formRef = ref()
const form = reactive({
  adminUsername: 'admin', adminPassword: '', confirmPassword: '',
  systemName: 'HelloAI', systemDescription: ''
})

type FormRuleValidator = (_rule: unknown, value: string, callback: (err?: Error | string) => void) => void
const validateConfirm: FormRuleValidator = (_rule, value, callback) => {
  if (value !== form.adminPassword) callback(new Error('两次输入的密码不一致'))
  else callback()
}
const rules = {
  adminPassword: [{ required: true, min: 6, message: '密码至少6位' }],
  confirmPassword: [{ required: true }, { validator: validateConfirm }],
  systemName: [{ required: true, message: '请输入项目名称' }]
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await settingsApi.initialize({
      adminUsername: form.adminUsername, adminPassword: form.adminPassword,
      systemName: form.systemName, systemDescription: form.systemDescription
    })
    ElMessage.success('初始化完成，请登录')
    router.push('/login')
  } catch (e: any) { ElMessage.error("初始化失败，请重试") } finally { submitting.value = false }
}
</script>

<style scoped>
.setup-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #7C3AED 0%, #A78BFA 50%, #06B6D4 100%);
  padding: 16px;
}
.setup-card {
  width: 480px;
  max-width: 100%;
  padding: 40px;
  background: var(--ha-surface-elevated);
  border-radius: var(--ha-radius-xl);
  box-shadow: var(--ha-shadow-lg);
}
.setup-header { text-align: center; margin-bottom: 32px; }
.setup-header h2 { margin: 12px 0 4px; font-size: 22px; font-weight: 700; color: var(--ha-ink); }
.setup-header p { font-size: 14px; color: var(--ha-muted); margin: 0; }

@media (max-width: 640px) {
  .setup-card {
    padding: 24px 16px;
  }
  .setup-card :deep(.el-form-item__label) {
    width: auto !important;
    padding-bottom: 0;
  }
}
</style>

