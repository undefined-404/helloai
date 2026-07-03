<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>积分流水</span>
          <el-button size="small" type="primary" @click="showAdjust = true">手动调整</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="agentId" label="AgentID" width="80" />
        <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
        <el-table-column label="变动" width="80">
          <template #default="{ row }">
            <span :style="{ color: row.delta > 0 ? 'var(--ha-success)' : 'var(--ha-danger)', fontWeight:'600' }">
              {{ row.delta > 0 ? '+' : '' }}{{ row.delta }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="balance" label="余额" width="80" />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无记录" />

      <el-dialog v-model="showAdjust" title="手动调整积分" width="480px" top="10vh">
        <el-form ref="adjFormRef" :model="adjForm" label-width="100px">
          <el-form-item label="AgentID" prop="agentId">
            <el-input-number v-model="adjForm.agentId" :min="1" style="width:100%" />
          </el-form-item>
          <el-form-item label="变动分值" prop="scoreDelta">
            <el-input-number v-model="adjForm.scoreDelta" style="width:100%" />
          </el-form-item>
          <el-form-item label="原因" prop="reason">
            <el-input v-model="adjForm.reason" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="showAdjust=false">取消</el-button>
          <el-button type="primary" :loading="adjusting" @click="handleAdjust">确认</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { rewardApi } from '@/api/reward'

const list = ref<any[]>([])
const loading = ref(false)
const showAdjust = ref(false)
const adjusting = ref(false)
const adjFormRef = ref()
const adjForm = ref({ agentId: 1, scoreDelta: 1, reason: '' })

async function load() {
  loading.value = true
  try {
    const lb: any = await rewardApi.leaderboard()
    list.value = lb || []
  } finally { loading.value = false }
}

async function handleAdjust() {
  adjusting.value = true
  try {
    await rewardApi.adjust(adjForm.value)
    ElMessage.success('调整成功')
    showAdjust.value = false
    load()
  } finally { adjusting.value = false }
}

onMounted(() => load())
</script>

<style scoped>
.page { max-width: 1200px; }
</style>
