<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Agent 管理</span>
          <el-button size="small" type="primary" @click="registerDialog = true">注册 Agent</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="roleTag(row.role)" size="small">{{ row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status==='ACTIVE'?'success':'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="score" label="积分" width="80" sortable />
        <el-table-column prop="modelType" label="模型" min-width="120" />
        <el-table-column prop="createTime" label="注册时间" width="170" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="router.push('/sub-tasks?agent='+row.id)">任务</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无 Agent" />

      <el-dialog v-model="registerDialog" title="注册新 Agent" width="480px" top="10vh">
        <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" />
          </el-form-item>
          <el-form-item label="角色" prop="role">
            <el-select v-model="form.role" style="width:100%">
              <el-option label="规划器 PLANNER" value="PLANNER" />
              <el-option label="执行器 EXECUTOR" value="EXECUTOR" />
              <el-option label="审查器 REVIEWER" value="REVIEWER" />
              <el-option label="巡逻 PATROL" value="PATROL" />
            </el-select>
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="registerDialog=false">取消</el-button>
          <el-button type="primary" :loading="registering" @click="handleRegister">注册</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'

const router = useRouter()
const list = ref<any[]>([])
const loading = ref(false)
const registerDialog = ref(false)
const registering = ref(false)
const formRef = ref()
const form = ref({ name: '', role: 'EXECUTOR', description: '' })
const rules = { name: [{ required: true }], role: [{ required: true }] }

const roleMap: Record<string, string> = { PLANNER: '', EXECUTOR: 'primary', REVIEWER: 'success', PATROL: 'warning' }
function roleTag(role: string) { return roleMap[role] || '' }

async function load() {
  loading.value = true
  try { list.value = await agentApi.list() } finally { loading.value = false }
}

async function handleRegister() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  registering.value = true
  try {
    const res: any = await agentApi.register({ name: form.value.name, role: form.value.role, description: form.value.description })
    ElMessage.success('注册成功，API Key: ' + (res.apiKey || ''))
    registerDialog.value = false
    load()
  } finally { registering.value = false }
}

onMounted(() => load())
</script>

<style scoped>
.page { max-width: 1200px; }
</style>
