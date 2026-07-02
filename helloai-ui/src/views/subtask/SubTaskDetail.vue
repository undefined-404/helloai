<template>
  <div v-loading="loading">
    <el-card v-if="subTask" style="margin-bottom:16px">
      <template #header>
        <div class="card-header">
          <span><el-icon><Document /></el-icon> 子任务详情 #{{ subTask.id }}</span>
          <div>
            <el-tag :type="SUB_TASK_STATUS_MAP[subTask.status]?.type" size="small">
              {{ SUB_TASK_STATUS_MAP[subTask.status]?.label }}
            </el-tag>
          </div>
        </div>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="标题" :span="2">{{ subTask.title }}</el-descriptions-item>
        <el-descriptions-item label="任务ID">{{ subTask.taskId }}</el-descriptions-item>
        <el-descriptions-item label="负责人Agent">{{ subTask.assignedAgent || '-' }}</el-descriptions-item>
        <el-descriptions-item label="综合评分">{{ subTask.compositeScore ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="评分等级">
          <el-tag v-if="subTask.scoreGrade" :type="SCORE_GRADE_MAP[subTask.scoreGrade]?.type" size="small">
            {{ SCORE_GRADE_MAP[subTask.scoreGrade]?.label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="截止时间">{{ subTask.deadline || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ subTask.createTime }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ subTask.updateTime }}</el-descriptions-item>
      </el-descriptions>
      <h4 style="margin:16px 0 8px">内容</h4>
      <pre style="background:#f5f7fa;padding:12px;border-radius:4px;white-space:pre-wrap">{{ subTask.content }}</pre>
      <div style="margin-top:16px;display:flex;gap:8px">
        <el-button v-if="subTask.status==='PENDING'" type="primary" @click="handleAction('claim')">认领</el-button>
        <el-button v-if="subTask.status==='ASSIGNED'" type="primary" @click="handleAction('start')">开始执行</el-button>
        <el-button v-if="subTask.status==='IN_PROGRESS'" type="warning" @click="handleAction('submit')">提交审查</el-button>
        <el-button v-if="['IN_PROGRESS','ASSIGNED','REWORK'].includes(subTask.status)" type="danger" @click="handleAction('block')">标记阻塞</el-button>
        <el-button v-if="subTask.status==='BLOCKED'" @click="handleReassign">重新分配</el-button>
        <el-button v-if="subTask.status==='REVIEW'" type="success" @click="reviewDialog=true">审查</el-button>
      </div>
    </el-card>

    <el-card v-if="reviews.length">
      <template #header><span>审查记录</span></template>
      <el-table :data="reviews" border stripe>
        <el-table-column prop="round" label="轮次" width="60" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="row.result==='APPROVED'?'success':'danger'" size="small">{{ row.result }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="score" label="评分" width="60" />
        <el-table-column prop="issues" label="问题" min-width="160" show-overflow-tooltip />
        <el-table-column prop="comment" label="备注" min-width="160" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
    </el-card>

    <el-dialog v-model="reviewDialog" title="提交审查" width="500px" top="10vh">
      <el-form ref="reviewFormRef" :model="reviewForm" :rules="reviewRules" label-width="80px">
        <el-form-item label="审查结果" prop="result">
          <el-radio-group v-model="reviewForm.result">
            <el-radio value="APPROVED">通过</el-radio>
            <el-radio value="REJECTED">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="评分" prop="score">
          <el-rate v-model="reviewForm.score" :max="5" show-score />
        </el-form-item>
        <el-form-item label="问题描述" prop="issues">
          <el-input v-model="reviewForm.issues" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="reviewForm.comment" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialog=false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReview">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { subTaskApi } from '@/api/subTask'
import { reviewApi } from '@/api/review'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP } from '@/types'

const route = useRoute()
const loading = ref(false)
const subTask = ref<any>(null)
const reviews = ref<any[]>([])
const reviewDialog = ref(false)
const submitting = ref(false)
const reviewFormRef = ref()
const reviewForm = ref({ result: 'APPROVED', score: 3, issues: '', comment: '' })
const reviewRules = { result: [{ required: true }], score: [{ required: true }] }

async function load() {
  loading.value = true
  try {
    subTask.value = await subTaskApi.getById(Number(route.params.id))
    reviews.value = await reviewApi.list(subTask.value.id)
  } finally { loading.value = false }
}

async function handleAction(action: string) {
  try {
    const apiCalls: Record<string, Function> = {
      claim: (id: number) => subTaskApi.claim(id, 1),
      start: (id: number) => subTaskApi.start(id),
      submit: (id: number) => subTaskApi.submit(id),
      block: (id: number) => subTaskApi.block(id)
    }
    await apiCalls[action](subTask.value.id)
    ElMessage.success('操作成功')
    load()
  } catch {}
}

async function handleReassign() {}

async function submitReview() {
  const valid = await reviewFormRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await reviewApi.create({
      subTaskId: subTask.value.id,
      result: reviewForm.value.result as any,
      score: reviewForm.value.score,
      issues: reviewForm.value.issues,
      comment: reviewForm.value.comment,
      reworkAgentId: null
    })
    ElMessage.success('审查提交成功')
    reviewDialog.value = false
    load()
  } finally { submitting.value = false }
}

onMounted(() => load())
</script>