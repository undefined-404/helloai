<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span><el-icon><Folder /></el-icon> 附件管理</span>
        <el-button size="small" type="primary" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table :data="list" border stripe v-loading="loading" style="width:100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
      <el-table-column prop="fileType" label="类型" width="80" />
      <el-table-column prop="fileSize" label="大小" width="100">
        <template #default="{ row }">{{ (row.fileSize / 1024).toFixed(1) }} KB</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status==='ACTIVE'?'success':'info'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="上传时间" width="170" />
    </el-table>
    <el-empty v-if="!list.length && !loading" description="暂无附件" />
  </el-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { attachmentApi } from '@/api/attachment'

const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await attachmentApi.list() } finally { loading.value = false } }
onMounted(() => load())
</script>