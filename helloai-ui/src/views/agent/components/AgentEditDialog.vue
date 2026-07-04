<template>
  <el-dialog
    v-model="visible"
    title="编辑 Agent"
    width="480px"
    top="5vh"
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
          <el-option label="巡查者 PATROL" value="PATROL" />
        </el-select>
      </el-form-item>
      <el-form-item label="模型类型">
        <el-input v-model="form.modelType" placeholder="如: gpt-4, claude-3" />
      </el-form-item>
      <el-form-item label="专业化" v-if="form.role === 'EXECUTOR'">
        <el-select v-model="form.specializationSlug" clearable placeholder="选择 Agent 专业化" style="width:100%">
          <el-option label="无 (默认)" value="" />
          <el-option label="AI酱瓜-后端" value="executor-backend" />
          <el-option label="AI小珂-前端" value="executor-frontend" />
          <el-option label="AI小云-运维" value="executor-devops" />
          <el-option label="AI小吴-调研" value="executor-researcher" />
          <el-option label="AI小安-测试" value="executor-tester" />
        </el-select>
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
import type { AgentListItem } from '@/types'

const props = defineProps<{ modelValue: boolean; agent: AgentListItem | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; saved: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const saving = ref(false)
const formRef = ref()
const form = reactive({ name: '', role: 'EXECUTOR', modelType: '', specializationSlug: '', remark: '' })
const rules = { name: [{ required: true, message: '请输入名称', trigger: 'blur' }] }

watch(() => props.agent, (a) => {
  if (a) {
    form.name = a.name
    form.role = a.role
    form.modelType = ''
    form.specializationSlug = ''
    form.remark = a.description || ''
  }
}, { immediate: true })

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !props.agent) return
  saving.value = true
  try {
    await agentApi.updateProfile(props.agent.id, {
      name: form.name,
      modelType: form.modelType || undefined,
      specializationSlug: form.specializationSlug || undefined,
      remark: form.remark
    })
    ElMessage.success('更新成功')
    visible.value = false
    emit('saved')
  } finally { saving.value = false }
}
</script>
