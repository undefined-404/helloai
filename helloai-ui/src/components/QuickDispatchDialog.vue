<template>
  <el-dialog
    :model-value="modelValue"
    title="快速派发子任务"
    width="720px"
    top="6vh"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="onOpen"
    @closed="resetForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" v-loading="loading">
      <el-form-item label="任务" prop="taskId">
        <el-select v-model="form.taskId" placeholder="选择任务" filterable style="width:100%" @change="onTaskChange">
          <el-option v-for="t in tasks" :key="t.id" :label="t.title" :value="t.id" />
          <template #footer>
            <div style="padding:6px;display:flex;gap:6px">
              <el-input v-model="newTaskTitle" placeholder="新任务标题" size="small" />
              <el-button size="small" type="primary" :loading="creatingTask" @click="createTaskInline">新建</el-button>
            </div>
          </template>
        </el-select>
      </el-form-item>

      <el-form-item label="模块" prop="moduleId">
        <el-select v-model="form.moduleId" placeholder="选择模块（可空）" filterable clearable style="width:100%" :disabled="!form.taskId" @visible-change="(v: boolean) => v && loadModules()">
          <el-option v-for="m in modules" :key="m.id" :label="m.name" :value="m.id" />
          <template #footer>
            <div style="padding:6px;display:flex;gap:6px">
              <el-input v-model="newModuleName" placeholder="新模块名称" size="small" :disabled="!form.taskId" />
              <el-button size="small" type="primary" :loading="creatingModule" :disabled="!form.taskId || !newModuleName.trim()" @click="createModuleInline">新建</el-button>
            </div>
          </template>
        </el-select>
      </el-form-item>

      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" placeholder="子任务标题" maxlength="120" show-word-limit />
      </el-form-item>

      <el-form-item label="任务内容" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="3" placeholder="描述要做什么 / 期望产出" />
      </el-form-item>

      <el-form-item label="验收标准">
        <el-input v-model="form.acceptance" type="textarea" :rows="2" placeholder="（可选）明确可验证的通过条件" />
      </el-form-item>

      <el-form-item label="优先级">
        <el-select v-model="form.priority" style="width:140px">
          <el-option label="HIGH" value="HIGH" />
          <el-option label="MEDIUM" value="MEDIUM" />
          <el-option label="LOW" value="LOW" />
        </el-select>
      </el-form-item>

      <el-form-item label="执行 Agent" prop="agentIds">
        <el-select
          v-model="form.agentIds"
          multiple
          filterable
          placeholder="至少选 1 个 EXECUTOR（自动过滤 CLI_CLIENT）"
          style="width:100%"
        >
          <el-option
            v-for="a in availableAgents"
            :key="a.id"
            :label="`${a.name} (${roleLabel(a.role)})`"
            :value="a.id"
          />
        </el-select>
        <div style="margin-top:4px;color:var(--ha-muted);font-size:12px">
          共 {{ availableAgents.length }} 个可用 EXECUTOR（role=EXECUTOR 且 accessType=CLI_CLIENT）
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="onSubmit">派发</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { taskApi } from '@/api/task'
import { moduleApi } from '@/api/module'
import { agentApi } from '@/api/agent'
import { subTaskApi } from '@/api/subTask'
import type { Task, ModuleItem, Agent, CreateSubTaskPayload } from '@/types'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'done': []
}>()

// ── 表单 ──
const formRef = ref<FormInstance | null>(null)
const form = reactive({
  taskId: '' as string,
  moduleId: '' as string,
  title: '',
  description: '',
  acceptance: '',
  priority: 'MEDIUM',
  agentIds: [] as string[]
})
const rules: FormRules = {
  taskId: [{ required: true, message: '任务不能为空', trigger: 'change' }],
  title: [{ required: true, message: '标题不能为空', trigger: 'blur' }],
  description: [{ required: true, message: '任务内容不能为空', trigger: 'blur' }],
  agentIds: [{ type: 'array', required: true, min: 1, message: '至少选 1 个 Agent', trigger: 'change' }]
}

// ── 数据 ──
const tasks = ref<Task[]>([])
const modules = ref<ModuleItem[]>([])
const agents = ref<Agent[]>([])
const loading = ref(false)
const submitting = ref(false)
const creatingTask = ref(false)
const creatingModule = ref(false)
const newTaskTitle = ref('')
const newModuleName = ref('')

// 派发候选：role=EXECUTOR 且 accessType=CLI_CLIENT
const availableAgents = computed(() =>
  agents.value.filter(a => a.role === 'EXECUTOR' && a.accessType === 'CLI_CLIENT')
)
const ROLE_LABELS: Record<string, string> = {
  PLANNER: '规划者', EXECUTOR: '执行者', REVIEWER: '审查者', PATROL: '巡检者'
}
function roleLabel(role: string) { return ROLE_LABELS[role] || role }

// ── 加载 ──
async function onOpen() {
  loading.value = true
  try {
    const [t, a] = await Promise.all([taskApi.list(), agentApi.list({ role: 'EXECUTOR' })])
    tasks.value = Array.isArray(t) ? t : []
    agents.value = Array.isArray(a) ? a : []
  } catch (e) {
    ElMessage.error('加载任务/Agent 失败')
  } finally {
    loading.value = false
  }
}

async function loadModules() {
  if (!form.taskId) return
  try {
    modules.value = await moduleApi.list(form.taskId)
  } catch (e) {
    modules.value = []
  }
}

function onTaskChange() {
  form.moduleId = ''
  modules.value = []
}

// ── 行内新建 ──
async function createTaskInline() {
  if (!newTaskTitle.value.trim()) return
  creatingTask.value = true
  try {
    const created = await taskApi.create({ title: newTaskTitle.value.trim() })
    if (created && created.id) {
      tasks.value.push(created)
      form.taskId = created.id as string
      newTaskTitle.value = ''
      ElMessage.success('任务已新建')
    }
  } catch (e: any) {
    ElMessage.error('新建任务失败：' + (e?.message || '未知错误'))
  } finally {
    creatingTask.value = false
  }
}

async function createModuleInline() {
  if (!form.taskId || !newModuleName.value.trim()) return
  creatingModule.value = true
  try {
    const created = await moduleApi.create(form.taskId, { name: newModuleName.value.trim() })
    if (created && created.id) {
      modules.value.push(created)
      form.moduleId = created.id as string
      newModuleName.value = ''
      ElMessage.success('模块已新建')
    }
  } catch (e: any) {
    ElMessage.error('新建模块失败：' + (e?.message || '未知错误'))
  } finally {
    creatingModule.value = false
  }
}

// ── 提交（逐项 Promise.allSettled，不用 batch 端点）──
async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const basePayload: Omit<CreateSubTaskPayload, 'assignedAgent'> = {
      taskId: form.taskId,
      moduleId: form.moduleId || undefined,
      title: form.title,
      description: form.description,
      acceptance: form.acceptance || undefined,
      priority: form.priority
    }
    // 并行逐项派发；任一失败不影响其他项
    const settled = await Promise.allSettled(
      form.agentIds.map(agentId =>
        subTaskApi.create({ ...basePayload, assignedAgent: agentId })
      )
    )
    const successCount = settled.filter(r => r.status === 'fulfilled').length
    const failures = settled
      .map((r, i) => ({ r, agentId: form.agentIds[i] }))
      .filter(({ r }) => r.status === 'rejected')

    if (successCount > 0) {
      ElMessage.success(`派发成功 ${successCount} 个子任务`)
    }
    if (failures.length > 0) {
      const names = failures.map(f => lookupAgentName(f.agentId)).join('、')
      const firstErr = (failures[0].r as PromiseRejectedResult).reason?.message || '未知错误'
      ElMessage.error(`失败 ${failures.length} 个：${names}（首条错误：${firstErr}）`)
    }
    if (successCount > 0) {
      emit('done')
      emit('update:modelValue', false)
    }
  } catch (e: any) {
    ElMessage.error('派发失败：' + (e?.message || '未知错误'))
  } finally {
    submitting.value = false
  }
}

function lookupAgentName(agentId: string): string {
  const a = agents.value.find(x => String(x.id) === String(agentId))
  return a?.name || String(agentId)
}

function resetForm() {
  form.taskId = ''
  form.moduleId = ''
  form.title = ''
  form.description = ''
  form.acceptance = ''
  form.priority = 'MEDIUM'
  form.agentIds = []
  newTaskTitle.value = ''
  newModuleName.value = ''
  modules.value = []
}
</script>

<style scoped>
:deep(.el-dialog__body) { padding-top: 12px; padding-bottom: 4px; }
</style>