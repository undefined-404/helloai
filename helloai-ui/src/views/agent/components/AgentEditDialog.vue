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
        <!-- V52 三段式：模型能力锁定 tag（不可取消，自动并入） -->
        <div v-if="form.modelType && !skillDegraded" class="skill-cap-row">
          <el-tag
            v-for="s in skillCap"
            :key="s"
            size="small"
            type="primary"
            effect="plain"
            disable-transitions
          >{{ skillLabel(s) }}（模型能力）</el-tag>
        </div>
        <el-alert
          v-if="form.modelType && skillDegraded"
          title="模型未上架，建议使用已上架模型；技能将按默认规则处理"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom:8px"
        />
        <el-select
          v-model="form.skills"
          multiple
          filterable
          allow-create
          default-first-option
          placeholder="选择或输入技能（回车可自定义）"
          style="width:100%"
          :loading="skillOptionsLoading"
        >
          <el-option
            v-for="opt in skillSelectOptions"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
            :disabled="opt.disabled"
            :title="opt.disabled ? '该模型不支持此技能' : ''"
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
import { ref, watch, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'
import { AGENT_SKILL_OPTIONS } from '@/constants/agentSkills'
import type { AgentListItem } from '@/types'

const props = defineProps<{ modelValue: boolean; agent: AgentListItem | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; saved: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const saving = ref(false)
const formRef = ref()
// V47/A2: skills 为能力声明列表（多选下拉 + 自定义，回显后整体替换提交；删光 = 清空）
// §6.74: 模型类型/专业化已移除——外部 AI Agent 用自身模型、内部 LLM 统一走系统配置默认模型
// V52: 内部 LLM Agent 编辑时可切换模型（modelType 回显），技能区按模型能力三段式渲染
const form = reactive({ name: '', role: 'EXECUTOR', remark: '', apiKey: '', modelType: '', skills: [] as string[] })
const rules = { name: [{ required: true, message: '请输入名称', trigger: 'blur' }] }

watch(() => props.agent, (a) => {
  if (a) {
    form.name = a.name
    form.role = a.role
    form.remark = a.description || ''
    form.apiKey = a.apiKey || ''
    form.modelType = a.modelType || ''
    form.skills = Array.isArray(a.skills) ? [...a.skills] : []
  }
}, { immediate: true })

// ── V52 技能区三段式：模型能力锁定 + 可选项白名单 + 降级提示 ──
const skillCap = ref<string[]>([])       // capabilitySkills（模型能力锁定，自动并入 form.skills）
const skillAvailable = ref<string[]>([]) // availableOptionalSkills（可扩展白名单）
const skillDegraded = ref(false)         // 模型未识别：降级为全量可编辑 + 提示
const skillOptionsLoading = ref(false)

function skillLabel(v: string) {
  return AGENT_SKILL_OPTIONS.find(o => o.value === v)?.label || v
}

async function loadSkillOptions(modelType: string) {
  skillOptionsLoading.value = true
  try {
    const res = await agentApi.skillOptions(modelType)
    skillCap.value = res.capabilitySkills || []
    skillAvailable.value = res.availableOptionalSkills || []
    skillDegraded.value = !!res.degraded
    // 能力锁定项强制并入（不可取消）
    for (const s of skillCap.value) {
      if (!form.skills.includes(s)) form.skills.push(s)
    }
  } catch {
    skillDegraded.value = true
  } finally {
    skillOptionsLoading.value = false
  }
}

watch(() => form.modelType, (mt) => {
  // 切模型/清空：先移除旧能力锁定项，避免残留不可用技能
  for (const s of skillCap.value) {
    const idx = form.skills.indexOf(s)
    if (idx >= 0) form.skills.splice(idx, 1)
  }
  skillCap.value = []
  skillAvailable.value = []
  skillDegraded.value = false
  if (mt) {
    loadSkillOptions(mt)
  }
})

const skillSelectOptions = computed(() => {
  const isDriven = !!form.modelType && !skillDegraded.value
  if (!isDriven) {
    // 外部 Agent / 模型留空 / 未识别降级：全量可编辑
    return AGENT_SKILL_OPTIONS.map(o => ({ ...o, disabled: false }))
  }
  // 能力驱动：锁定项由 tag 展示（下拉剔除），白名单可编辑，其余标准技能置灰（自定义仍可输入）
  return AGENT_SKILL_OPTIONS
    .filter(o => !skillCap.value.includes(o.value))
    .map(o => ({ ...o, disabled: !skillAvailable.value.includes(o.value) }))
})

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
      skills: form.skills,
      // V52: 切换模型时显式提交（重新校验），未变更则后端保留原值
      modelType: form.modelType || undefined
    })
    ElMessage.success('更新成功')
    visible.value = false
    emit('saved')
  } finally { saving.value = false }
}
</script>

<style scoped>
.field-hint { width: 100%; margin-top: 4px; color: var(--ha-muted); font-size: 12px; line-height: 1.5; }

/* V52 技能区：模型能力锁定 tag 行 */
.skill-cap-row {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
</style>
