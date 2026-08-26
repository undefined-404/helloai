<template>
  <el-container class="app-shell">
    <el-aside
      :width="collapsed ? '64px' : '220px'"
      class="app-sidebar"
      :class="{ collapsed }"
    >
      <div class="sidebar-header">
        <div class="sidebar-logo">
          <el-icon
            :size="22"
            class="sidebar-logo-icon"
          >
            <MagicStick />
          </el-icon>
          <span
            v-show="!collapsed"
            class="sidebar-title"
          >HelloAI</span>
        </div>
        <button
          class="theme-toggle"
          type="button"
          :title="themeStore.theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'"
          :aria-label="themeStore.theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'"
          @click="themeStore.toggleTheme()"
        >
          <el-icon :size="15">
            <Moon v-if="themeStore.theme === 'dark'" />
            <Sunny v-else />
          </el-icon>
        </button>
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
          <span>概述</span>
        </el-menu-item>
        <!-- V29 对话式需求澄清入口（置于概述与任务管理之间） -->
        <el-menu-item index="/requirement-chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>对话新建</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>
          <span>任务管理</span>
        </el-menu-item>
        <el-menu-item index="/sub-tasks">
          <el-icon><Document /></el-icon>
          <span>子任务</span>
        </el-menu-item>
        <!-- V25 死信池：复用子任务列表页 + 状态筛选，不建独立页面 -->
        <el-menu-item index="/sub-tasks?status=DEAD_LETTER">
          <el-icon><Warning /></el-icon>
          <span>死信池</span>
        </el-menu-item>
        <el-menu-item index="/agents">
          <el-icon><User /></el-icon>
          <span>Agent管理</span>
        </el-menu-item>
        <!-- v2.0: Prompt 管理菜单移除，Agent 接入改用 onboarding 弹窗 -->
        <!-- <el-menu-item index="/prompts">
          <el-icon><EditPen /></el-icon>
          <span>Prompt 管理</span>
        </el-menu-item> -->
        <el-menu-item index="/inbox">
          <el-icon><Message /></el-icon>
          <span>收件箱</span>
        </el-menu-item>
        <el-menu-item index="/reviews">
          <el-icon><Select /></el-icon>
          <span>审查中心</span>
        </el-menu-item>
        <!-- Phase 5 质量度量看板 -->
        <el-menu-item index="/quality-dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>质量看板</span>
        </el-menu-item>
        <!-- 2026-08-16: 积分流水 / 活动流 / 规则配置 暂未启用，先隐藏菜单入口（页面文件保留） -->
        <!-- <el-menu-item index="/rewards">
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
        </el-menu-item> -->
        <el-menu-item index="/duty-leases">
          <el-icon><Clock /></el-icon>
          <span>打卡上班</span>
        </el-menu-item>
        <el-menu-item index="/attachments">
          <el-icon><Folder /></el-icon>
          <span>附件管理</span>
        </el-menu-item>
        <!-- 系统设置：管理员一级入口（头像下拉保留作为冗余入口） -->
        <el-menu-item
          v-if="isAdmin"
          index="/settings"
        >
          <el-icon><Tools /></el-icon>
          <span>系统设置</span>
        </el-menu-item>
      </el-menu>

      <div
        class="sidebar-footer"
        :class="{ collapsed: collapsed }"
      >
        <el-dropdown
          trigger="click"
          placement="top-start"
        >
          <div class="sidebar-user">
            <el-avatar
              :size="28"
              icon="UserFilled"
              class="user-avatar"
            />
            <span
              v-show="!collapsed"
              class="user-name"
            >{{ userName }}</span>
            <el-icon
              v-show="!collapsed"
              :size="12"
              class="user-arrow"
            >
              <ArrowDown />
            </el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-if="isAdmin"
                @click="openPasswordDialog"
              >
                <el-icon><Lock /></el-icon>修改密码
              </el-dropdown-item>
              <el-dropdown-item
                v-if="isAdmin"
                @click="router.push('/settings')"
              >
                <el-icon><Tools /></el-icon>系统设置
              </el-dropdown-item>
              <el-dropdown-item @click="handleLogout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          v-show="!collapsed"
          :icon="collapsed ? Expand : Fold"
          text
          class="collapse-btn"
          :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="collapsed = !collapsed"
        />
      </div>
      <el-button
        v-show="collapsed"
        :icon="Expand"
        text
        class="collapse-btn-mini"
        @click="collapsed = !collapsed"
      />
    </el-aside>

    <el-main class="app-content">
      <router-view v-slot="{ Component }">
        <transition
          name="page-fade"
          mode="out-in"
        >
          <component :is="Component" />
        </transition>
      </router-view>
    </el-main>

    <ChangePasswordDialog
      v-model="passwordDialogVisible"
      :on-changed="handlePasswordChanged"
    />
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Clock, DataAnalysis, Expand, Fold, Moon, Sunny, Warning } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const themeStore = useThemeStore()
const collapsed = ref(false)
const passwordDialogVisible = ref(false)

// 单一来源：store 已是 sessionStorage 的唯一镜像
const isAdmin = auth.isAdmin
const userName = computed(() => auth.displayName)

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/sub-tasks')) {
    // 死信池菜单与子任务菜单同路由，按 query.status 区分高亮
    return route.query.status === 'DEAD_LETTER' ? '/sub-tasks?status=DEAD_LETTER' : '/sub-tasks'
  }
  return path
})

function openPasswordDialog() {
  passwordDialogVisible.value = true
}

async function handleLogout() {
  try {
    if (isAdmin && auth.adminToken) {
      await authApi.logout()
    }
  } catch {
    // 会话不存在时忽略服务端登出失败，继续清理前端状态
  } finally {
    auth.logout()
    router.push('/login')
  }
}

function handlePasswordChanged() {
  // 修改密码成功：清空登录态 + 跳 login（组件已经关闭弹窗，这里只负责后续副作用）
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.app-shell { height: 100vh; overflow: hidden; }

.app-sidebar {
  background: linear-gradient(160deg, var(--ha-sidebar-bg-start) 0%, var(--ha-sidebar-bg-mid) 55%, var(--ha-sidebar-bg-end) 100%);
  border-right: 1px solid var(--ha-sidebar-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  contain: layout style;
  position: relative;
  isolation: isolate;
  /* 主题切换过渡 */
  transition: background var(--ha-duration-normal) var(--ha-ease-out),
              border-color var(--ha-duration-normal) var(--ha-ease-out);
}

.sidebar-title,
.sidebar-menu .el-menu-item span,
.user-name,
.user-arrow,
.collapse-btn {
  transition: opacity var(--ha-duration-fast) var(--ha-ease-out);
}

.app-sidebar::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, var(--ha-sidebar-grid) 1px, transparent 1px),
    linear-gradient(to bottom, var(--ha-sidebar-grid) 1px, transparent 1px);
  background-size: 28px 28px;
  pointer-events: none;
  z-index: 0;
}

.app-sidebar::after {
  content: '';
  position: absolute;
  bottom: 10%;
  left: 5%;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  filter: blur(60px);
  background: color-mix(in srgb, var(--ha-accent-cyan) 15%, transparent);
  pointer-events: none;
  z-index: 0;
}

.sidebar-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 16px;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}

/* 收起态：logo 与主题切换钮纵向排列 */
.app-sidebar.collapsed .sidebar-header {
  flex-direction: column;
  height: auto;
  padding: 12px 0 8px;
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sidebar-logo-icon {
  color: var(--ha-sidebar-text);
}

.sidebar-title {
  color: var(--ha-sidebar-text);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  white-space: nowrap;
}

/* 主题切换钮：圆形图标钮，侧边栏内自绘（不经 EP，避免亮暗底适配负担） */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border: 1px solid var(--ha-sidebar-border);
  border-radius: 50%;
  background: var(--ha-sidebar-hover);
  color: var(--ha-sidebar-text-muted);
  cursor: pointer;
  transition: color var(--ha-duration-fast) var(--ha-ease-out),
              background-color var(--ha-duration-fast) var(--ha-ease-out),
              border-color var(--ha-duration-fast) var(--ha-ease-out);
}
.theme-toggle:hover {
  color: var(--ha-sidebar-text);
  background: var(--ha-sidebar-active);
}

.sidebar-menu {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
  background: transparent !important;
  border-right: none !important;
  position: relative;
  z-index: 1;
}

.sidebar-menu .el-menu-item {
  color: var(--ha-sidebar-text-muted) !important;
  border-radius: 8px !important;
  margin: 1px 8px !important;
  padding: 0 12px !important;
  height: 38px !important;
  line-height: 38px !important;
  font-size: 14px !important;
  transition: all var(--ha-duration-fast) var(--ha-ease-out) !important;
}

.sidebar-menu .el-menu-item:hover {
  background: var(--ha-sidebar-hover) !important;
  color: var(--ha-sidebar-text) !important;
}

.sidebar-menu .el-menu-item.is-active {
  background: var(--ha-primary) !important;
  color: #fff !important;
  font-weight: 600;
  box-shadow: 0 4px 14px rgba(124, 58, 237, 0.35);
}

.sidebar-menu .el-menu-item .el-icon {
  font-size: 18px;
}

.sidebar-footer {
  padding: 10px 12px;
  border-top: 1px solid var(--ha-sidebar-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.sidebar-footer.collapsed {
  justify-content: center;
  padding: 10px 0;
}

.sidebar-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  flex: 1;
  min-width: 0;
}

.user-avatar {
  --el-avatar-bg-color: rgba(124, 58, 237, 0.45) !important;
  flex-shrink: 0;
}

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--ha-sidebar-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-arrow {
  color: var(--ha-sidebar-text-muted);
  flex-shrink: 0;
  margin-left: auto;
}

.collapse-btn {
  color: var(--ha-sidebar-text-muted) !important;
  flex-shrink: 0;
  padding: 4px !important;
}

.collapse-btn:hover {
  color: var(--ha-sidebar-text) !important;
}

.collapse-btn-mini {
  color: var(--ha-sidebar-text-muted) !important;
  margin: 10px auto;
  display: block;
}

.collapse-btn-mini:hover {
  color: var(--ha-sidebar-text) !important;
}

.app-content {
  background: var(--ha-content-bg);
  padding: clamp(16px, 2vw, 32px);
  overflow-y: auto;
  height: 100vh;
  transition: background-color var(--ha-duration-normal) var(--ha-ease-out);
}

.page-fade-enter-active { animation: ha-fade-up 350ms var(--ha-ease-out) both; }
.page-fade-leave-active { animation: ha-fade-in 150ms var(--ha-ease-in-out) reverse both; }

.app-shell {
  padding-top: env(safe-area-inset-top);
}

@media (max-width: 1024px) {
  .app-sidebar {
    width: 64px !important;
  }

  .app-sidebar .sidebar-title,
  .app-sidebar .user-name,
  .app-sidebar .user-arrow,
  .app-sidebar .sidebar-footer .collapse-btn {
    display: none !important;
  }

  .app-sidebar .collapse-btn-mini {
    display: block !important;
  }

  .app-sidebar.sidebar-expanded {
    width: 220px !important;
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 1000;
    box-shadow: var(--ha-shadow-lg);
  }

  .app-sidebar.sidebar-expanded .sidebar-title,
  .app-sidebar.sidebar-expanded .user-name,
  .app-sidebar.sidebar-expanded .user-arrow {
    display: flex !important;
    flex-direction: column;
  }

  .app-sidebar.sidebar-expanded .collapse-btn {
    display: flex !important;
  }

  .app-sidebar.sidebar-expanded .collapse-btn-mini {
    display: none !important;
  }
}

@media (pointer: coarse) {
  .sidebar-menu .el-menu-item {
    height: 44px !important;
    line-height: 44px !important;
  }

  .collapse-btn,
  .collapse-btn-mini {
    min-height: 44px;
    min-width: 44px;
  }
}

@media (max-width: 480px) {
  .app-content {
    padding: 8px !important;
  }
}
</style>
