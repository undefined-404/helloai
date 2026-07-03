<template>
  <el-container class="app-shell">
    <!-- Sidebar -->
    <el-aside :width="collapsed ? '64px' : '240px'" class="app-sidebar">
      <div class="sidebar-header">
        <div class="sidebar-logo">
          <el-icon :size="24" color="#2B5FD9"><MagicStick /></el-icon>
          <span v-show="!collapsed" class="sidebar-title">HelloAI</span>
        </div>
        <el-button
          v-show="!collapsed"
          :icon="Fold"
          text
          class="collapse-btn"
          @click="collapsed = !collapsed"
        />
      </div>

      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        class="sidebar-menu"
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

      <div v-show="!collapsed" class="sidebar-footer">
        <el-menu class="sidebar-menu" router>
          <el-menu-item index="/settings">
            <el-icon><Tools /></el-icon>
            <span>系统设置</span>
          </el-menu-item>
        </el-menu>
      </div>
      <div v-show="collapsed" class="sidebar-footer-collapsed">
        <el-menu :collapse="true" :collapse-transition="false" class="sidebar-menu" router>
          <el-menu-item index="/settings">
            <el-icon><Tools /></el-icon>
          </el-menu-item>
        </el-menu>
      </div>
    </el-aside>

    <!-- Main -->
    <el-container class="app-main-area">
      <!-- Header -->
      <header class="app-topbar">
        <div class="topbar-left">
          <el-button
            :icon="collapsed ? Expand : Fold"
            text
            class="topbar-toggle"
            @click="collapsed = !collapsed"
          />
          <el-breadcrumb separator="/" class="topbar-breadcrumb">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.meta?.title">{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="topbar-right">
          <el-dropdown trigger="click">
            <span class="user-info">
              <el-avatar :size="28" icon="UserFilled" class="user-avatar" />
              <span class="user-name">{{ userName }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- Content -->
      <el-main class="app-content">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Fold, Expand, SwitchButton } from '@element-plus/icons-vue'

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
/* ---- Shell ---- */
.app-shell {
  height: 100vh;
  overflow: hidden;
}

/* ---- Sidebar ---- */
.app-sidebar {
  background: #111318;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width var(--ha-duration-normal) var(--ha-ease-out);
  z-index: 100;
}

.sidebar-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
}

.sidebar-title {
  color: #ffffff;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  white-space: nowrap;
}

.collapse-btn {
  color: rgba(255, 255, 255, 0.4) !important;
  flex-shrink: 0;
}
.collapse-btn:hover {
  color: rgba(255, 255, 255, 0.7) !important;
}

.sidebar-menu {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.sidebar-footer {
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  padding: 4px 0;
  flex-shrink: 0;
}

.sidebar-footer-collapsed {
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

/* ---- Top Bar ---- */
.app-topbar {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--ha-bg);
  border-bottom: 1px solid var(--ha-border-light);
  padding: 0 16px;
  flex-shrink: 0;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.topbar-toggle {
  color: var(--ha-muted) !important;
  font-size: 18px;
}
.topbar-toggle:hover {
  color: var(--ha-ink) !important;
}

.topbar-breadcrumb {
  margin-left: 4px;
}

.topbar-right {
  display: flex;
  align-items: center;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--ha-radius-md);
  transition: background var(--ha-duration-fast);
}
.user-info:hover {
  background: var(--ha-surface);
}

.user-avatar {
  --el-avatar-bg-color: var(--ha-primary-muted) !important;
}

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--ha-ink);
}

/* ---- Content ---- */
.app-content {
  background: var(--ha-surface);
  padding: 20px;
  overflow-y: auto;
  height: calc(100vh - 48px);
}

/* ---- Page Transition ---- */
.page-fade-enter-active {
  animation: ha-fade-up 350ms var(--ha-ease-out) both;
}
.page-fade-leave-active {
  animation: ha-fade-in 150ms var(--ha-ease-in-out) reverse both;
}
</style>
