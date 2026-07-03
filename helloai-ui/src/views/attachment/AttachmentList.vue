<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>附件管理</span>
          <el-button size="small" type="primary" @click="load">刷新</el-button>
        </div>
      </template>
      <el-empty description="暂无附件" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'

const list = ref<any[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    // TODO: load attachments
  } finally { loading.value = false }
}
onMounted(() => load())
</script>

<style scoped>
.page { max-width: 1200px; }
</style>
