<template>
  <div class="login-container">
    <el-card class="login-card" shadow="always">
      <div class="login-header">
        <el-icon :size="40" color="#409EFF"><MagicStick /></el-icon>
        <h2>HelloAI 管理平台</h2>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="0" size="large" @keyup.enter="handleLogin">
        <el-form-item prop="type">
          <el-radio-group v-model="form.type" class="login-type">
            <el-radio value="admin" border>管理员登录</el-radio>
            <el-radio value="agent" border>Agent 密钥登录</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.type === 'admin'" prop="username">
          <el-input v-model="form.username" placeholder="用户名" clearable />
        </el-form-item>
        <el-form-item prop="credential">
          <el-input
            v-model="form.credential"
            :placeholder="form.type === 'admin' ? '密码' : 'API Key'"
            :type="form.type === 'admin' ? 'password' : 'text'"
            clearable
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" style="width:100%" @click="handleLogin">登 录</el-button>
        </el-form-item>
        <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/api/request'

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

const form = reactive({
  type: 'admin',
  username: 'admin',
  credential: ''
})

const rules = {
  credential: [{ required: true, message: '请输入凭证', trigger: 'blur' }]
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
</script>

<style scoped>
.login-container { height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); }
.login-card { width: 420px; }
.login-header { text-align: center; margin-bottom: 24px; }
.login-header h2 { margin: 12px 0 0; font-weight: 600; color: #303133; }
.login-type { display: flex; width: 100%; }
.login-type .el-radio { flex: 1; justify-content: center; }
.error-msg { color: #f56c6c; font-size: 13px; text-align: center; margin: 0; }
</style>
