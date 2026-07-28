<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑任务' : '新建任务'"
    width="520px"
    top="8vh"
    append-to-body
    @open="initForm"
    @close="$emit('close')"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="70px">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" placeholder="任务标题" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="4"
          placeholder="任务描述（PLANNER 拆解子任务的依据）"
        />
      </el-form-item>
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
import type { Task } from '@/types'

// task 为 null 时是新建模式，否则为编辑模式
const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const isEdit = computed(() => !!props.task)

const formRef = ref<FormInstance>()
const form = reactive({ title: '', description: '' })
const rules: FormRules = {
  title: [{ required: true, message: '请输入任务标题', trigger: 'blur' }]
}
const saving = ref(false)

function initForm() {
  form.title = props.task?.title ?? ''
  form.description = props.task?.description ?? ''
  formRef.value?.clearValidate()
}

async function handleSave() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    if (props.task) {
      await taskApi.update(props.task.id, { title: form.title, description: form.description })
      ElMessage.success('任务已更新')
    } else {
      await taskApi.create({ title: form.title, description: form.description })
      ElMessage.success('任务已创建，已通知 PLANNER 拆解')
    }
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { saving.value = false }
}
</script>
