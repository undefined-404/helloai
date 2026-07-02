<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span><el-icon><Notification /></el-icon> 活动流</span>
        <el-button size="small" type="primary" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table :data="list" border stripe v-loading="loading" style="width:100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="agentId" label="Agent" width="80" />
      <el-table-column prop="subTaskId" label="子任务" width="80" />
      <el-table-column prop="action" label="行为" min-width="160" />
      <el-table-column prop="createTime" label="时间" width="170" />
    </el-table>
    <el-empty v-if="!list.length && !loading" description="暂无活动" />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { activityApi } from '@/api/activity'

const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await activityApi.list() } finally { loading.value = false } }
onMounted(() => load())
</script>