<template>
  <el-dialog
    :model-value="modelValue"
    title="修改密码"
    width="420px"
    append-to-body
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
    @closed="resetForm"
  >
    <el-form
      label-position="top"
      class="password-form"
    >
      <el-form-item label="当前密码">
        <el-input
          v-model="form.currentPassword"
          type="password"
          show-password
          placeholder="请输入当前登录密码"
        />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          placeholder="至少 6 位，建议使用高强度密码"
        />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          placeholder="再次输入新密码"
        />
      </el-form-item>
      <p class="password-form-tip">
        修改成功后会退出当前会话，请使用新密码重新登录。
      </p>
    </el-form>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="cancel">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="changing"
          @click="submit"
        >
          保存新密码
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'
import { msg } from '@/utils/messages'

/**
 * 修改密码弹窗。
 * - 由父组件通过 v-model 控制显隐；
 * - 修改成功后调用 useAuthStore.logout()（由父组件传入 onChanged 钩子触发跳转）。
 */
const props = defineProps<{
  modelValue: boolean
  onChanged?: () => void
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const changing = ref(false)
const form = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: ''
})

function resetForm() {
  form.currentPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
}

function cancel() {
  emit('update:modelValue', false)
}

async function submit() {
  if (!form.currentPassword) {
    ElMessage.error(msg.password.currentRequired)
    return
  }
  if (!form.newPassword) {
    ElMessage.error(msg.password.newRequired)
    return
  }
  if (form.newPassword.length < 6) {
    ElMessage.error(msg.password.minLength)
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.error(msg.password.mismatch)
    return
  }

  changing.value = true
  try {
    await authApi.changePassword({
      currentPassword: form.currentPassword,
      newPassword: form.newPassword
    })
    ElMessage.success(msg.password.success)
    emit('update:modelValue', false)
    props.onChanged?.()
  } catch (e: any) {
    ElMessage.error(e?.message || msg.password.failed)
  } finally {
    changing.value = false
  }
}
</script>

<style scoped>
.password-form-tip {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ha-muted);
  line-height: 1.6;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>