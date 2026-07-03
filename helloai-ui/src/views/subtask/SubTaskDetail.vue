<template>
  <div class="page ha-entrance-up" v-loading="loading">
    <el-card v-if="item">
      <template #header>
        <div class="card-header">
          <span>子任务详情 #{{ item.id }}</span>
          <el-button size="small" @click="router.push('/sub-tasks')">返回列表</el-button>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="标题">{{ item.title }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getSubTaskStatusMeta(item.status)?.type || 'info'" size="small">
            {{ getSubTaskStatusMeta(item.status)?.label || item.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="负责人">{{ item.assignedAgent || '-' }}</el-descriptions-item>
        <el-descriptions-item label="评分">
          <el-tag v-if="item.scoreGrade" :type="SCORE_GRADE_MAP[item.scoreGrade]?.type || 'info'" size="small">
            {{ SCORE_GRADE_MAP[item.scoreGrade]?.label || item.scoreGrade }}
          </el-tag>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">{{ item.createTime }}</el-descriptions-item>
        <el-descriptions-item label="内容" :span="2">{{ item.content || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { subTaskApi } from '@/api/subTask'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'
import type { SubTask } from '@/types'

const route = useRoute()
const router = useRouter()
const item = ref<SubTask | null>(null)
const loading = ref(false)

function getSubTaskStatusMeta(status: SubTask['status']) {
  return SUB_TASK_STATUS_MAP[status]
}

onMounted(async () => {
  loading.value = true
  try {
    item.value = await subTaskApi.getById(Number(route.params.id))
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.page { max-width: 900px; }
</style>
