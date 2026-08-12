<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑任务' : '新建任务'"
    width="600px"
    top="6vh"
    append-to-body
    @open="initForm"
    @close="$emit('close')"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" v-loading="loading">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" placeholder="任务标题" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          placeholder="任务描述（PLANNER 拆解子任务的依据）"
        />
      </el-form-item>
      <el-form-item label="SLA 分钟">
        <el-input-number v-model="form.slaMinutes" :min="1" :max="100000" controls-position="right" placeholder="无时限" style="width:180px" />
        <div class="field-hint">空=无时限；计划确认时按「确认时刻+SLA」下发子任务 deadline</div>
      </el-form-item>

      <!-- V47/A1: 执行策略折叠区块（缺省即回落默认，全空=不指定） -->
      <el-collapse class="policy-collapse">
        <el-collapse-item title="执行策略（V47，可选）" name="policy">
          <el-form-item label="拆解 Planner">
            <el-select v-model="form.plannerAgentId" clearable filterable placeholder="不指定（自动选择）" style="width:100%">
              <el-option
                v-for="o in plannerOptions"
                :key="String(o.id)"
                :label="o.name"
                :value="o.id"
                :disabled="!o.selectable"
              />
            </el-select>
            <div class="field-hint">指定后拆解/澄清固定由该 Planner 承担；失效时自动回退</div>
          </el-form-item>
          <el-form-item label="核验 Reviewer">
            <el-select v-model="form.reviewerAgentId" clearable filterable placeholder="不指定（自动选择）" style="width:100%">
              <el-option
                v-for="a in reviewerAgents"
                :key="a.id"
                :label="a.name"
                :value="a.id"
              />
            </el-select>
            <div class="field-hint">指定后子任务自动核验固定由该 Reviewer 承担；失效时自动回退</div>
          </el-form-item>
          <el-form-item label="执行白名单">
            <el-select v-model="form.executorAgentIds" multiple clearable filterable placeholder="不限定（全部 EXECUTOR 可选）" style="width:100%">
              <el-option
                v-for="a in executorAgents"
                :key="a.id"
                :label="a.name"
                :value="a.id"
              />
            </el-select>
            <div class="field-hint">仅选中的 EXECUTOR 可承接本任务子任务；空=不限定</div>
          </el-form-item>
          <el-form-item label="回退策略">
            <el-select v-model="form.fallbackPolicy" clearable placeholder="AUTO（默认）" style="width:100%">
              <el-option label="AUTO：外部 Agent 失败正常回退 API_KEY_LLM 保底" value="AUTO" />
              <el-option label="RESTRICTED：仅回退白名单内的 API_KEY_LLM" value="RESTRICTED" />
              <el-option label="NONE：禁止自动回退，改打人工介入标记" value="NONE" />
            </el-select>
          </el-form-item>
          <el-form-item label="任务难度">
            <el-select v-model="form.difficulty" clearable placeholder="MEDIUM（默认）" style="width:100%">
              <el-option label="LOW" value="LOW" />
              <el-option label="MEDIUM" value="MEDIUM" />
              <el-option label="HIGH（视为禁止自动回退）" value="HIGH" />
            </el-select>
          </el-form-item>
          <el-form-item label="要求技能">
            <el-select
              v-model="form.requiredSkills"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入技能（回车可自定义）"
              style="width:100%"
            >
              <el-option
                v-for="opt in skillOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <div class="field-hint">非空时执行者必须全部具备（AND 语义）；空=不限制</div>
          </el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { taskApi } from '@/api/task'
import { agentApi } from '@/api/agent'
import { clarifyApi } from '@/api/clarify'
import { AGENT_SKILL_OPTIONS } from '@/constants/agentSkills'
import type { Task, TaskAgentPolicy, Agent, PlannerOption, LongId } from '@/types'

const skillOptions = AGENT_SKILL_OPTIONS

// task 为 null 时是新建模式，否则为编辑模式
const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const isEdit = computed(() => !!props.task)

const formRef = ref<FormInstance>()
// A1: 执行策略各键 null/空 = 不指定（提交时仅组装非空键；编辑时 null 字段后端保持现状）
const form = reactive({
  title: '',
  description: '',
  slaMinutes: null as number | null,
  plannerAgentId: null as LongId | null,
  reviewerAgentId: null as LongId | null,
  executorAgentIds: [] as LongId[],
  fallbackPolicy: null as 'AUTO' | 'RESTRICTED' | 'NONE' | null,
  difficulty: null as 'LOW' | 'MEDIUM' | 'HIGH' | null,
  requiredSkills: [] as string[]
})
const rules: FormRules = {
  title: [{ required: true, message: '请输入任务标题', trigger: 'blur' }]
}
const saving = ref(false)
const loading = ref(false)

// ── 候选数据（planner 走 listPlannerOptions 带在班/可选判定，reviewer/executor 按角色拉取）──
const plannerOptions = ref<PlannerOption[]>([])
const reviewerAgents = ref<Agent[]>([])
const executorAgents = ref<Agent[]>([])

async function loadOptions() {
  loading.value = true
  try {
    const [planners, reviewers, executors] = await Promise.all([
      clarifyApi.plannerOptions(),
      agentApi.list({ role: 'REVIEWER' }),
      agentApi.list({ role: 'EXECUTOR' })
    ])
    plannerOptions.value = Array.isArray(planners) ? planners : []
    reviewerAgents.value = Array.isArray(reviewers) ? reviewers : []
    executorAgents.value = Array.isArray(executors) ? executors : []
  } catch { /* 候选加载失败不阻断表单，提交时后端仍会校验 */ }
  finally { loading.value = false }
}

// A1: 编辑态回显（task.agentPolicy/requiredSkills/slaMinutes 后端随实体返回）
function initForm() {
  form.title = props.task?.title ?? ''
  form.description = props.task?.description ?? ''
  form.slaMinutes = props.task?.slaMinutes ?? null
  const p = props.task?.agentPolicy
  form.plannerAgentId = p?.plannerAgentId ?? null
  form.reviewerAgentId = p?.reviewerAgentId ?? null
  form.executorAgentIds = Array.isArray(p?.executorAgentIds) ? [...(p!.executorAgentIds!)] : []
  form.fallbackPolicy = p?.fallbackPolicy ?? null
  form.difficulty = p?.difficulty ?? null
  form.requiredSkills = Array.isArray(props.task?.requiredSkills) ? [...(props.task!.requiredSkills!)] : []
  formRef.value?.clearValidate()
  loadOptions()
}

// 组装 policy Map：仅含非空键；全空返回 null（创建=不设置，编辑=保持现状）
function buildPolicy(): TaskAgentPolicy | null {
  const policy: TaskAgentPolicy = {}
  if (form.plannerAgentId != null && form.plannerAgentId !== '') policy.plannerAgentId = form.plannerAgentId
  if (form.executorAgentIds.length > 0) policy.executorAgentIds = [...form.executorAgentIds]
  if (form.reviewerAgentId != null && form.reviewerAgentId !== '') policy.reviewerAgentId = form.reviewerAgentId
  if (form.fallbackPolicy) policy.fallbackPolicy = form.fallbackPolicy
  if (form.difficulty) policy.difficulty = form.difficulty
  return Object.keys(policy).length > 0 ? policy : null
}

async function handleSave() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    const payload = {
      title: form.title,
      description: form.description,
      slaMinutes: form.slaMinutes,
      agentPolicy: buildPolicy(),
      requiredSkills: form.requiredSkills
    }
    if (props.task) {
      await taskApi.update(String(props.task.id), payload)
      ElMessage.success('任务已更新')
    } else {
      await taskApi.create(payload)
      ElMessage.success('任务已创建，已通知 PLANNER 拆解')
    }
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { saving.value = false }
}
</script>

<style scoped>
.policy-collapse { margin-top: -4px; border-top: none; }
.policy-collapse :deep(.el-collapse-item__header) { font-weight: 600; }
.field-hint { width: 100%; margin-top: 4px; color: var(--ha-muted); font-size: 12px; line-height: 1.5; }
</style>
