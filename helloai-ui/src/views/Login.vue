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
        <el-form-item prop="credential">
          <el-input v-model="form.credential" :placeholder="form.type === 'admin' ? '管理员令牌' : 'API Key'" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" style="width:100%" @click="handleLogin">登 录</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

const router = useRouter()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  type: 'admin',
  credential: ''
})

const rules = {
  credential: [{ required: true, message: '请输入凭证', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    if (form.type === 'admin') {
      sessionStorage.setItem('adminToken', form.credential)
      sessionStorage.setItem('adminUser', 'Admin')
    } else {
      sessionStorage.setItem('agentKey', form.credential)
      sessionStorage.setItem('adminUser', 'Agent')
    }
    ElMessage.success('登录成功')
    router.push('/dashboard')
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
</style>