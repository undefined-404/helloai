<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <span>系统设置</span>
      </template>
      <el-form ref="formRef" :model="form" label-width="140px" class="settings-form">
        <el-divider content-position="left">基础配置</el-divider>
        <el-form-item label="平台名称">
          <el-input v-model="form.platformName" />
        </el-form-item>
        <el-form-item label="外部访问地址">
          <el-input v-model="form.externalUrl" placeholder="https://example.com" />
        </el-form-item>
        <el-divider content-position="left">通知配置</el-divider>
        <el-form-item label="通知方式">
          <el-checkbox-group v-model="form.notifyChannels">
            <el-checkbox label="web" disabled>站内通知</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSave">保存设置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { settingsApi } from '@/api/settings'

const formRef = ref()
const form = reactive({
  platformName: 'HelloAI',
  externalUrl: '',
  notifyChannels: ['web']
})

async function load() {
  try {
    const config = await settingsApi.getConfig()
    if (config) {
      form.platformName = config['system.name'] || 'HelloAI'
      form.externalUrl = config['helloai.base-url'] || ''
    }
  } catch (e: any) {
    ElMessage.error('加载配置失败')
  }
}

async function handleSave() {
  try {
    await settingsApi.batchUpdateConfig({
      'system.name': form.platformName,
      'helloai.base-url': form.externalUrl
    })
    ElMessage.success('保存成功')
  } catch (e: any) {
    ElMessage.error('保存失败')
  }
}

onMounted(() => load())
</script>

<style scoped>
.page { max-width: 1200px; }
.settings-form {
  max-width: 600px;
}

@media (max-width: 768px) {
  .settings-form {
    max-width: 100%;
  }
  .settings-form :deep(.el-form-item__label) {
    width: auto !important;
    padding-bottom: 0;
  }
}
</style>
