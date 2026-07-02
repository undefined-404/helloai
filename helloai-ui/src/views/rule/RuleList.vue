<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span><el-icon><Setting /></el-icon> 规则配置</span>
        <el-button size="small" type="primary" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table :data="list" border stripe v-loading="loading" style="width:100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small">{{ row.ruleType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="priority" label="优先级" width="80" />
      <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
      <el-table-column prop="updateTime" label="更新时间" width="170" />
    </el-table>
    <el-empty v-if="!list.length && !loading" description="暂无规则" />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ruleApi } from '@/api/rule'

const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await ruleApi.list() } finally { loading.value = false } }
onMounted(() => load())
</script>