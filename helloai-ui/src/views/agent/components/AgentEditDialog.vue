<template>
  <el-dialog
    v-model="visible"
    title="编辑 Agent"
    width="480px"
    top="5vh"
    append-to-body
    @close="$emit('close')"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="角色" prop="role">
        <el-select v-model="form.role" style="width:100%">
          <el-option label="规划者 PLANNER" value="PLANNER" />
          <el-option label="执行者 EXECUTOR" value="EXECUTOR" />
          <el-option label="审查者 REVIEWER" value="REVIEWER" />
        </el-select>
      </el-form-item>
      <el-form-item label="API Key">
        <el-input :model-value="form.apiKey" readonly>
          <template #append>
            <el-button @click="copyApiKey">复制</el-button>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="技能">
        <el-select
          v-model="form.skills"
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
        <div class="field-hint">能力声明，任务「要求技能」按 AND 语义匹配；保存即整体替换</div>
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="职责简要" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'
import { AGENT_SKILL_OPTIONS } from '@/constants/agentSkills'
import type { AgentListItem } from '@/types'

const skillOptions = AGENT_SKILL_OPTIONS

const props = defineProps<{ modelValue: boolean; agent: AgentListItem | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; saved: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const saving = ref(false)
const formRef = ref()
// V47/A2: skills 为能力声明列表（多选下拉 + 自定义，回显后整体替换提交；删光 = 清空）
// §6.74: 模型类型/专业化已移除——外部 AI Agent 用自身模型、内部 LLM 统一走系统配置默认模型
const form = reactive({ name: '', role: 'EXECUTOR', remark: '', apiKey: '', skills: [] as string[] })
const rules = { name: [{ required: true, message: '请输入名称', trigger: 'blur' }] }

watch(() => props.agent, (a) => {
  if (a) {
    form.name = a.name
    form.role = a.role
    form.remark = a.description || ''
    form.apiKey = a.apiKey || ''
    form.skills = Array.isArray(a.skills) ? [...a.skills] : []
  }
}, { immediate: true })

function copyApiKey() {
  navigator.clipboard.writeText(form.apiKey)
  ElMessage.success('API Key 已复制到剪贴板')
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !props.agent) return
  saving.value = true
  try {
    await agentApi.updateProfile(props.agent.id, {
      name: form.name,
      remark: form.remark,
      skills: form.skills
    })
    ElMessage.success('更新成功')
    visible.value = false
    emit('saved')
  } finally { saving.value = false }
}
</script>

<style scoped>
.field-hint { width: 100%; margin-top: 4px; color: var(--ha-muted); font-size: 12px; line-height: 1.5; }
</style>
