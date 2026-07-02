<template>
  <el-container style="height:100vh">
    <el-aside :width="collapsed ? '64px' : '220px'" class="app-aside">
      <div class="logo-area">
        <el-icon :size="28" color="#409EFF"><MagicStick /></el-icon>
        <span v-show="!collapsed" class="logo-text">HelloAI</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        background-color="#001529"
        text-color="#ffffffa6"
        active-text-color="#fff"
        router
      >
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>概览</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>
          <span>任务管理</span>
        </el-menu-item>
        <el-menu-item index="/sub-tasks">
          <el-icon><Document /></el-icon>
          <span>子任务</span>
        </el-menu-item>
        <el-menu-item index="/agents">
          <el-icon><User /></el-icon>
          <span>Agent管理</span>
        </el-menu-item>
        <el-menu-item index="/reviews">
          <el-icon><Select /></el-icon>
          <span>审查中心</span>
        </el-menu-item>
        <el-menu-item index="/rewards">
          <el-icon><Coin /></el-icon>
          <span>积分流水</span>
        </el-menu-item>
        <el-menu-item index="/activity">
          <el-icon><Notification /></el-icon>
          <span>活动流</span>
        </el-menu-item>
        <el-menu-item index="/rules">
          <el-icon><Setting /></el-icon>
          <span>规则配置</span>
        </el-menu-item>
        <el-menu-item index="/attachments">
          <el-icon><Folder /></el-icon>
          <span>附件管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <el-button :icon="collapsed ? 'Expand' : 'Fold'" text @click="collapsed = !collapsed" />
        <el-breadcrumb separator="/" style="margin-left:16px">
          <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-if="route.meta?.title">{{ route.meta.title }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div style="flex:1" />
        <el-dropdown trigger="click">
          <span class="user-info">
            <el-avatar :size="28" icon="UserFilled" />
            <span style="margin-left:8px">{{ userName }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const userName = ref(sessionStorage.getItem('adminUser') || 'Admin')

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/sub-tasks')) return '/sub-tasks'
  return path
})

function handleLogout() {
  sessionStorage.clear()
  router.push('/login')
}
</script>

<style scoped>
.app-aside { background-color: #001529; overflow-y: auto; transition: width 0.3s; }
.logo-area { height: 60px; display: flex; align-items: center; justify-content: center; gap: 8px; border-bottom: 1px solid #ffffff1a; }
.logo-text { color: #fff; font-size: 18px; font-weight: 700; white-space: nowrap; }
.app-header { display: flex; align-items: center; background: #fff; border-bottom: 1px solid #e4e7ed; padding: 0 16px; height: 50px !important; }
.app-main { background: #f5f7fa; padding: 16px; overflow-y: auto; }
.user-info { display: flex; align-items: center; cursor: pointer; }
.el-menu { border-right: none; }
</style>