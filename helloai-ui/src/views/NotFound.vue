<template>
  <div class="not-found">
    <div class="not-found-code">404</div>
    <div class="not-found-desc">页面不存在或无权访问</div>
    <el-button
      type="primary"
      @click="goHome"
    >
      返回首页
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  // 动态路由已按权限注册，回首页由守卫决定落点
  router.push('/dashboard')
}
</script>

<style scoped>
.not-found {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--ha-muted);
}
.not-found-code {
  font-size: 64px;
  font-weight: 700;
  color: var(--ha-primary);
  letter-spacing: -0.03em;
}
.not-found-desc {
  font-size: 14px;
}
</style>
