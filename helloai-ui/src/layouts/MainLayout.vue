<template>
  <el-container class="app-shell">
    <el-aside :width="collapsed ? '64px' : '220px'" class="app-sidebar">
      <div class="sidebar-header">
        <div class="sidebar-logo">
          <el-icon :size="22" color="#fff"><MagicStick /></el-icon>
          <span v-show="!collapsed" class="sidebar-title">HelloAI</span>
        </div>
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
        <el-menu-item index="/duty-leases">
          <el-icon><Clock /></el-icon>
          <span>值班租约</span>
        </el-menu-item>
        <el-menu-item index="/attachments">
          <el-icon><Folder /></el-icon>
          <span>附件管理</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-footer" :class="{ collapsed: collapsed }">
        <el-dropdown trigger="click" placement="top-start">
          <div class="sidebar-user">
            <el-avatar :size="28" icon="UserFilled" class="user-avatar" />
            <span v-show="!collapsed" class="user-name">{{ userName }}</span>
            <el-icon v-show="!collapsed" :size="12" class="user-arrow"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="isAdmin" @click="openPasswordDialog">
                <el-icon><Lock /></el-icon>修改密码
              </el-dropdown-item>
              <el-dropdown-item v-if="isAdmin" @click="router.push('/settings')">
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
      <el-button v-show="collapsed" :icon="Expand" text class="collapse-btn-mini" @click="collapsed = !collapsed" />
    </el-aside>

    <el-main class="app-content">
      <router-view v-slot="{ Component }">
        <transition name="page-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </el-main>

    <el-dialog
      v-model="passwordDialogVisible"
      title="修改密码"
      width="420px"
      append-to-body
      destroy-on-close
      @closed="resetPasswordForm"
    >
      <el-form label-position="top" class="password-form">
        <el-form-item label="当前密码">
          <el-input
            v-model="passwordForm.currentPassword"
            type="password"
            show-password
            placeholder="请输入当前登录密码"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            placeholder="至少 6 位，建议使用高强度密码"
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            show-password
            placeholder="再次输入新密码"
          />
        </el-form-item>
        <p class="password-form-tip">修改成功后会退出当前会话，请使用新密码重新登录。</p>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="passwordDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="changingPassword" @click="handleChangePassword">
            保存新密码
          </el-button>
        </div>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Clock, Expand, Fold } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const passwordDialogVisible = ref(false)
const changingPassword = ref(false)

const loginType = sessionStorage.getItem('loginType') || (sessionStorage.getItem('agentKey') ? 'agent' : 'admin')
const isAdmin = loginType !== 'agent'
const userName = ref(sessionStorage.getItem(isAdmin ? 'adminUser' : 'agentName') || (isAdmin ? 'Admin' : 'Agent'))

const passwordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/sub-tasks')) return '/sub-tasks'
  return path
})

function clearAuthStorage() {
  sessionStorage.removeItem('adminToken')
  sessionStorage.removeItem('adminUser')
  sessionStorage.removeItem('agentKey')
  sessionStorage.removeItem('agentName')
  sessionStorage.removeItem('loginType')
}

function resetPasswordForm() {
  passwordForm.currentPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
}

function openPasswordDialog() {
  resetPasswordForm()
  passwordDialogVisible.value = true
}

async function handleLogout() {
  try {
    if (isAdmin && sessionStorage.getItem('adminToken')) {
      await authApi.logout()
    }
  } catch {
    // 会话不存在时忽略服务端登出失败，继续清理前端状态
  } finally {
    clearAuthStorage()
    router.push('/login')
  }
}

async function handleChangePassword() {
  if (!passwordForm.currentPassword) {
    ElMessage.error('请输入当前密码')
    return
  }
  if (!passwordForm.newPassword) {
    ElMessage.error('请输入新密码')
    return
  }
  if (passwordForm.newPassword.length < 6) {
    ElMessage.error('新密码至少 6 位')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.error('两次输入的新密码不一致')
    return
  }

  changingPassword.value = true
  try {
    await authApi.changePassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword
    })
    ElMessage.success('密码已更新，请重新登录')
    passwordDialogVisible.value = false
    clearAuthStorage()
    router.push('/login')
  } catch (e: any) {
    ElMessage.error(e?.message || '修改密码失败')
  } finally {
    changingPassword.value = false
  }
}
</script>

<style scoped>
.app-shell { height: 100vh; overflow: hidden; }

.app-sidebar {
  background: linear-gradient(135deg, #7C3AED 0%, #A78BFA 50%, #06B6D4 100%);
  background-size: 400% 400%;
  animation: sidebar-aurora 18s ease infinite;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  contain: layout style;
  position: relative;
  isolation: isolate;
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
    linear-gradient(to right, rgba(255, 255, 255, 0.03) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
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
  background: rgba(6, 182, 212, 0.15);
  pointer-events: none;
  animation: sidebar-blur-float 16s ease-in-out infinite;
  will-change: transform, filter;
  z-index: 0;
}

@keyframes sidebar-aurora {
  0% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
  100% { background-position: 0% 50%; }
}

@keyframes sidebar-blur-float {
  0% { transform: translate3d(0, 0, 0) scale(1); }
  50% { transform: translate3d(-40px, 30px, 0) scale(1.15); }
  100% { transform: translate3d(0, 0, 0) scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .app-sidebar { animation: none; background-size: auto; }
  .app-sidebar::after { animation: none; }
}

.sidebar-header {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sidebar-title {
  color: #fff;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  white-space: nowrap;
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
  color: #fff !important;
}

.sidebar-menu .el-menu-item.is-active {
  background: var(--ha-sidebar-active) !important;
  color: #fff !important;
  font-weight: 600;
}

.sidebar-menu .el-menu-item .el-icon {
  font-size: 18px;
}

.sidebar-footer {
  padding: 10px 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.10);
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
  --el-avatar-bg-color: rgba(255, 255, 255, 0.20) !important;
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
  color: #fff !important;
}

.collapse-btn-mini {
  color: var(--ha-sidebar-text-muted) !important;
  margin: 10px auto;
  display: block;
}

.collapse-btn-mini:hover {
  color: #fff !important;
}

.app-content {
  background: var(--ha-surface);
  padding: clamp(16px, 2vw, 32px);
  overflow-y: auto;
  height: 100vh;
}

.password-form-tip {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ha-muted);
  line-height: 1.6;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
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
