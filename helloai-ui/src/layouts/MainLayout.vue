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
        @select="onMenuSelect"
      >
        <template
          v-for="menu in visibleMenus"
          :key="menu.path"
        >
          <el-sub-menu
            v-if="menu.children && menu.children.length > 0"
            :index="menu.path || menu.code"
          >
            <template #title>
              <el-icon><component :is="iconOf(menu.icon)" /></el-icon>
              <span>{{ menu.name }}</span>
            </template>
            <el-menu-item
              v-for="child in menu.children"
              :key="child.path"
              :index="child.path || child.code"
            >
              <el-icon><component :is="iconOf(child.icon)" /></el-icon>
              <span>{{ child.name }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item
            v-else
            :index="menu.path || menu.code"
          >
            <el-icon><component :is="iconOf(menu.icon)" /></el-icon>
            <span>{{ menu.name }}</span>
          </el-menu-item>
        </template>
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
                v-if="settingsTarget"
                @click="router.push(settingsTarget)"
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
          <!-- keep-alive：仅缓存 meta.keepAlive 的页面（BASE-3.1，默认不缓存） -->
          <keep-alive :include="cachedViews">
            <component :is="Component" />
          </keep-alive>
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
import { computed, onMounted, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import {
  ArrowDown, Expand, Fold, Lock, Menu, Moon, Sunny, SwitchButton, Tools, UserFilled
} from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { rbacApi } from '@/api/rbac'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import type { SysPermission } from '@/types'

/** DB 菜单图标列存 @element-plus/icons-vue 组件名，动态映射；未知图标回退 Menu */
function iconOf(name?: string | null): Component {
  if (name) {
    const icons = ElementPlusIconsVue as Record<string, Component>
    if (icons[name]) return icons[name]
  }
  return Menu
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const themeStore = useThemeStore()
const collapsed = ref(false)
const passwordDialogVisible = ref(false)

// 单一来源：store 已是 sessionStorage 的唯一镜像
const isAdmin = auth.isAdmin
const userName = computed(() => auth.displayName)

// G-012 菜单树 DB 化：菜单结构来自 /api/admin/menus/tree（服务端按权限码过滤 + parent 挂接），
// 不再由前端路由硬编码菜单结构；非 admin（外部 Agent）无菜单。
const menus = ref<SysPermission[]>([])
/** keep-alive 缓存白名单（BASE-3.1）：keepAlive=1 菜单的组件名（SFC 文件名） */
const cachedViews = ref<string[]>([])

async function loadMenuTree() {
  if (!isAdmin) return
  try {
    menus.value = await rbacApi.menuTree()
  } catch {
    // 菜单树拉取失败不阻塞页面：保留空菜单，仅顶部入口可用
    menus.value = []
  }
  cachedViews.value = collectKeepAliveNames(menus.value)
}

/** 收集 keepAlive=1 菜单的组件名（用于 <keep-alive :include>） */
function collectKeepAliveNames(nodes: SysPermission[]): string[] {
  const names: string[] = []
  for (const n of nodes) {
    if (n.keepAlive === 1 && n.component) {
      names.push(n.component.split('/').pop() as string)
    }
    if (n.children?.length) {
      names.push(...collectKeepAliveNames(n.children))
    }
  }
  return names
}

/**
 * 侧边栏可见菜单（BASE-3.1）：
 * hidden=1 的菜单不在侧边栏显示（路由仍注册可达）；子项全隐藏时父节点整体不显示。
 */
const visibleMenus = computed(() => {
  const filter = (nodes: SysPermission[]): SysPermission[] => {
    const out: SysPermission[] = []
    for (const n of nodes) {
      if (n.hidden === 1) continue
      if (n.children?.length) {
        const kids = filter(n.children)
        if (kids.length === 0) continue
        out.push({ ...n, children: kids })
      } else {
        out.push({ ...n, children: undefined })
      }
    }
    return out
  }
  return filter(menus.value)
})

/** 外链菜单映射（index → externalLink）：点击新窗口打开，不走前端路由 */
const externalMap = computed(() => {
  const map: Record<string, string> = {}
  const collect = (nodes: SysPermission[]) => {
    for (const n of nodes) {
      const key = n.path || n.code
      if (n.externalLink) map[key] = n.externalLink
      if (n.children?.length) collect(n.children)
    }
  }
  collect(menus.value)
  return map
})

/** el-menu 选择处理：外链 → 新窗口；普通菜单 → 路由跳转 */
function onMenuSelect(index: string) {
  const ext = externalMap.value[index]
  if (ext) {
    window.open(ext, '_blank', 'noopener,noreferrer')
    return
  }
  if (index && index !== route.path) {
    router.push(index)
  }
}

// 「系统设置」入口 = 菜单树中 settings 父节点第一个可见子菜单；
// 无可见子（如 ADMIN 无 user/role/permission:view）时隐藏入口，避免跳 404。
const settingsTarget = computed(() => {
  const node = menus.value.find((m) => m.path === '/settings')
  if (node?.children?.length) {
    const firstVisible = node.children.find((c) => c.hidden !== 1)
    return firstVisible?.path || null
  }
  return null
})

onMounted(loadMenuTree)

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
