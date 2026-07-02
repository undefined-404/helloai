<template>
  <el-card>
    <template #header>
      <span><el-icon><Tools /></el-icon> 系统设置</span>
    </template>
    <el-form label-width="140px" style="max-width:600px">
      <el-divider>基础配置</el-divider>
      <el-form-item label="平台名称">
        <el-input v-model="form.platformName" />
      </el-form-item>
      <el-form-item label="外部访问地址">
        <el-input v-model="form.externalUrl" placeholder="https://example.com" />
      </el-form-item>
      <el-divider>通知配置</el-divider>
      <el-form-item label="通知方式">
        <el-checkbox-group v-model="form.notifyChannels">
          <el-checkbox label="web" disabled>站内通知</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item>
        <el-button type="primary">保存设置</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { reactive } from 'vue'

const form = reactive({
  platformName: 'HelloAI',
  externalUrl: '',
  notifyChannels: ['web']
})
</script>