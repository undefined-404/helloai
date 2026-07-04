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
        <el-table-column label="注册时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" :width="ACTION.THREE" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button size="small" @click="router.push('/sub-tasks?agent='+row.id)">任务</el-button>
              <el-button size="small" @click="router.push('/inbox')">收件箱</el-button>
              <el-button size="small" type="warning" @click="handleResetKey(row)">重置Key</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无 Agent" />

      <el-dialog v-model="registerDialog" title="注册新 Agent" width="480px" top="5vh">
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
          <el-form-item label="专业化" v-if="form.role==='EXECUTOR'">
            <el-select v-model="form.specializationSlug" style="width:100%" clearable placeholder="选择 Agent 专业化配置">
              <el-option v-for="s in specOptions" :key="s.value" :label="s.label" :value="s.value" />
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentApi } from '@/api/agent'
import { ACTION } from '@/utils/tableConfig'
import { fmtTime } from '@/utils/tableConfig'

const router = useRouter()
const list = ref<any[]>([])
const loading = ref(false)
const registerDialog = ref(false)
const registering = ref(false)
const formRef = ref()
const form = ref({ name: '', role: 'EXECUTOR', specializationSlug: '', description: '' })
const rules = { name: [{ required: true }], role: [{ required: true }] }

const specOptions = [
  { label: '无 (默认)', value: '' },
  { label: 'AI酱瓜-后端', value: 'executor-backend' },
  { label: 'AI小珂-前端', value: 'executor-frontend' },
  { label: 'AI小云-运维', value: 'executor-devops' },
  { label: 'AI小吴-调研', value: 'executor-researcher' },
  { label: 'AI小安-测试', value: 'executor-tester' }
]

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

async function handleResetKey(row: any) {
  await ElMessageBox.confirm(`确定重置 ${row.name} 的 API Key？旧 Key 将立即失效。`, '确认重置', { type: 'warning' })
  try {
    const res = await agentApi.resetKey(row.id)
    ElMessage.success('新 API Key: ' + (res.apiKey || ''))
  } catch (e) { /* */ }
}

onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
</style>
