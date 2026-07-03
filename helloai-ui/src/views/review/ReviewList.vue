<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>审查中心</span>
          <el-button size="small" type="primary" @click="load">刷新</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="subTaskId" label="子任务ID" width="90" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="row.result==='APPROVED'?'success':'danger'" size="small">{{ row.result }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="score" label="评分" width="60" />
        <el-table-column prop="issues" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="comment" label="备注" min-width="160" show-overflow-tooltip />
        <el-table-column prop="round" label="轮次" width="60" />
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="router.push('/sub-tasks/'+row.subTaskId)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无审查记录" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { reviewApi } from '@/api/review'

const router = useRouter()
const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await reviewApi.list() } finally { loading.value = false } }
onMounted(() => load())
</script>

<style scoped>
.page { max-width: 1200px; }
</style>
