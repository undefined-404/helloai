<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>审查中心</span>
          <el-button
            size="small"
            type="primary"
            @click="load"
          >
            刷新
          </el-button>
        </div>
      </template>
      <el-table
        v-loading="loading"
        :data="list"
        border
        stripe
        style="width:100%"
      >
        <el-table-column
          label="子任务"
          min-width="100"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              link
              @click="router.push('/sub-tasks/'+row.subTaskId)"
            >
              #{{ row.subTaskId }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column
          label="结果"
          width="100"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.result==='APPROVED'?'success':'danger'"
              size="small"
            >
              {{ row.result }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="score"
          label="评分"
          width="70"
        />
        <el-table-column
          prop="issues"
          label="问题"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column
          prop="comment"
          label="评价"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          prop="round"
          label="轮次"
          width="70"
        />
        <el-table-column
          label="时间"
          width="170"
        >
          <template #default="{ row }">
            {{ fmtTime(row.createTime) }}
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!list.length && !loading"
        description="暂无审查记录"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { reviewApi } from '@/api/review'
import { fmtTime } from '@/utils/tableConfig'

const router = useRouter()
const list = ref<any[]>([])
const loading = ref(false)
async function load() { loading.value = true; try { list.value = await reviewApi.list() } finally { loading.value = false } }
onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
</style>
