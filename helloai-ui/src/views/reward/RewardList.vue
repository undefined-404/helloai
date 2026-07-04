<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>积分流水</span>
          <div>
            <el-button size="small" type="primary" @click="adjustDialog=true">手动调整</el-button>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
        <el-table-column label="变动" width="100">
          <template #default="{ row }">
            <span :style="{ color: row.delta > 0 ? 'var(--ha-success)' : 'var(--ha-danger)', fontWeight:'600' }">
              {{ row.delta > 0 ? '+' : '' }}{{ row.delta }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="balance" label="余额" width="80" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无积分记录" />

      <el-dialog v-model="adjustDialog" title="手动调整积分" width="480px" top="5vh">
        <el-form ref="adjustFormRef" :model="adjustForm" :rules="adjustRules" label-width="100px">
          <el-form-item label="Agent ID" prop="agentId">
            <el-input v-model.number="adjustForm.agentId" />
          </el-form-item>
          <el-form-item label="调整分数" prop="scoreDelta">
            <el-input-number v-model="adjustForm.scoreDelta" :min="-100" :max="100" />
          </el-form-item>
          <el-form-item label="原因" prop="reason">
            <el-input v-model="adjustForm.reason" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="adjustDialog=false">取消</el-button>
          <el-button type="primary" :loading="adjusting" @click="handleAdjust">确认</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { rewardApi } from '@/api/reward'
import { fmtTime } from '@/utils/tableConfig'

const list = ref<any[]>([])
const loading = ref(false)
const adjustDialog = ref(false)
const adjusting = ref(false)
const adjustFormRef = ref()
const adjustForm = reactive({ agentId: null as number | null, scoreDelta: 0, reason: '' })
const adjustRules = { agentId: [{ required: true }], scoreDelta: [{ required: true }], reason: [{ required: true }] }

async function load() { loading.value = true; try { list.value = await rewardApi.leaderboard() } finally { loading.value = false } }

async function handleAdjust() {
  const valid = await adjustFormRef.value?.validate().catch(() => false)
  if (!valid) return
  adjusting.value = true
  try {
    await rewardApi.adjust({ agentId: adjustForm.agentId!, scoreDelta: adjustForm.scoreDelta, reason: adjustForm.reason, subTaskId: null })
    ElMessage.success('调整成功')
    adjustDialog.value = false
    load()
  } finally { adjusting.value = false }
}

onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
</style>
