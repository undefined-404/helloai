<template>
  <div class="page list-page list-page--fill ha-entrance-up">
    <!-- 页头：图标块 + 标题（含条数）+ 副标题，右侧操作区 -->
    <div class="page-head">
      <div class="page-head-title">
        <div class="dash-icon-block primary">
          <el-icon><Menu /></el-icon>
        </div>
        <div>
          <h2 class="page-heading">
            菜单管理
            <span
              v-if="menuCount"
              class="query-stats"
            >共 {{ menuCount }} 条</span>
          </h2>
          <p class="page-subheading">
            侧边栏菜单、目录层级与路由注册的统一维护
          </p>
        </div>
      </div>
      <div class="page-head-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索菜单名/权限码"
          clearable
          class="filter-search"
          :prefix-icon="Search"
        />
        <el-button
          size="small"
          @click="load"
        >
          刷新
        </el-button>
        <el-button
          v-auth="'permission:add'"
          size="small"
          type="primary"
          @click="openCreate(null)"
        >
          <el-icon><Plus /></el-icon>
          <span>新增菜单</span>
        </el-button>
      </div>
    </div>

    <el-card class="ha-entrance-up list-card">
      <div class="table-wrapper">
        <el-table
          v-loading="loading"
          :data="filteredTree"
          row-key="id"
          border
          stripe
          default-expand-all
          style="width: 100%"
          empty-text="暂无菜单"
        >
          <el-table-column
            prop="name"
            label="菜单名称"
            min-width="190"
          />
          <el-table-column
            prop="code"
            label="权限码"
            min-width="145"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <code class="perm-code">{{ row.code }}</code>
            </template>
          </el-table-column>
          <el-table-column
            prop="path"
            label="路由路径"
            min-width="140"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <code
                v-if="row.path"
                class="perm-code"
              >{{ row.path }}</code>
              <span
                v-else
                class="muted"
              >—</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="component"
            label="组件"
            min-width="140"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span v-if="row.component">{{ row.component }}</span>
              <span
                v-else
                class="muted"
              >—（目录/聚合父，跳转首个可见子）</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="icon"
            label="图标"
            width="110"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span
                v-if="row.icon"
                class="icon-name"
              >{{ row.icon }}</span>
              <span
                v-else
                class="muted"
              >—</span>
            </template>
          </el-table-column>
          <el-table-column
            label="渲染"
            width="125"
          >
            <template #default="{ row }">
              <el-tag
                v-if="row.hidden === 1"
                size="small"
                type="warning"
                effect="plain"
              >
                隐藏
              </el-tag>
              <el-tag
                v-if="row.keepAlive === 1"
                size="small"
                type="success"
                effect="plain"
                class="tag-gap"
              >
                缓存
              </el-tag>
              <el-tag
                v-if="row.externalLink"
                size="small"
                type="info"
                effect="plain"
                class="tag-gap"
              >
                外链
              </el-tag>
              <span
                v-if="row.hidden !== 1 && row.keepAlive !== 1 && !row.externalLink"
                class="muted"
              >—</span>
            </template>
          </el-table-column>
          <!-- 操作列：172px = 「编辑 + 更多」两个 small 按钮 + 单元格 24px 内边距 + 12px 呼吸 -->
          <el-table-column
            label="操作"
            width="172"
            fixed="right"
          >
            <template #default="{ row }">
              <div class="action-cell">
                <!-- 与用户/任务等列表页同一布局：主操作外置，次级与危险操作收进「更多」 -->
                <el-button
                  v-auth="'permission:edit'"
                  size="small"
                  @click="openEdit(row)"
                >
                  编辑
                </el-button>
                <el-dropdown
                  v-if="hasMoreActions()"
                  trigger="click"
                  @command="(cmd: string) => handleMoreCommand(cmd, row)"
                >
                  <el-button
                    size="small"
                    :aria-label="`菜单「${row.name}」更多操作`"
                  >
                    更多
                    <el-icon class="el-icon--right">
                      <ArrowDown />
                    </el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <!-- 下拉项不用 v-auth（ElDropdownItem 根节点是 fragment，指令会被静默忽略） -->
                      <el-dropdown-item
                        v-if="auth.hasPermission('permission:add')"
                        command="child"
                      >
                        新增子菜单
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="auth.hasPermission('permission:delete')"
                        command="remove"
                      >
                        <span class="dropdown-danger">删除</span>
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- 新增 / 编辑 菜单 -->
    <el-dialog
      v-model="editVisible"
      :title="editingId ? '编辑菜单' : '新增菜单'"
      width="520px"
      class="ha-dialog--form ha-dialog--scrollable"
      :style="{ maxHeight: 'calc(100vh - 10vh)' }"
      destroy-on-close
    >
      <el-form
        ref="editFormRef"
        :model="form"
        :rules="editRules"
        label-width="90px"
      >
        <el-form-item
          label="权限码"
          prop="code"
        >
          <el-input
            v-model="form.code"
            placeholder="如 settings:view"
            :disabled="!!editingId"
          />
        </el-form-item>
        <el-form-item
          label="菜单名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            placeholder="如 系统设置"
          />
        </el-form-item>
        <el-form-item label="父级菜单">
          <el-tree-select
            v-model="form.parentId"
            :data="parentTreeOptions"
            :props="{ label: 'name', value: 'id' }"
            check-strictly
            clearable
            placeholder="留空为一级菜单"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="路由路径">
          <el-input
            v-model="form.path"
            placeholder="如 /system/users"
          />
        </el-form-item>
        <el-form-item label="组件路径">
          <el-input
            v-model="form.component"
            placeholder="如 system/UserList（相对 src/views；聚合父留空）"
          />
        </el-form-item>
        <el-form-item label="图标">
          <el-input
            v-model="form.icon"
            placeholder="Element Plus 图标名，如 User"
          />
        </el-form-item>
        <el-form-item label="外链地址">
          <el-input
            v-model="form.externalLink"
            placeholder="如 https://example.com（非空则新窗口打开，不注册路由）"
          />
        </el-form-item>
        <el-form-item label="隐藏菜单">
          <el-switch
            v-model="form.hidden"
            :active-value="1"
            :inactive-value="0"
          />
          <span class="muted hint">隐藏后侧边栏不显示，路由仍可达</span>
        </el-form-item>
        <el-form-item label="页面缓存">
          <el-switch
            v-model="form.keepAlive"
            :active-value="1"
            :inactive-value="0"
          />
          <span class="muted hint">开启后切换页面保留组件状态</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="form.sort"
            :min="0"
            :max="999"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="submitEdit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// ============================================================
// 菜单管理（BASE-2.2 收口）：仅维护 type=MENU 的菜单/目录
// - 数据源：/api/admin/permissions/tree（过滤 type=MENU）
// - 权限码（API 动作码）维护见「权限管理」页（PermissionList.vue）
// - 菜单与权限码同属 sys_permission 单一实体，故动作码复用
//   permission:add / permission:edit / permission:delete（后端 /api/admin/permissions 不变）
// ============================================================
import { ref, computed, onMounted } from 'vue'
import { Plus, Search, Menu, ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rbacApi } from '@/api/rbac'
import { useAuthStore } from '@/stores/auth'
import type { SysPermission, SysPermissionSaveRequest, LongId } from '@/types'

const list = ref<SysPermission[]>([])
const loading = ref(false)
const keyword = ref('')
// 下拉项不能用 v-auth（ElDropdownItem 根节点是 fragment），改用 hasPermission 过滤
const auth = useAuthStore()

/** 仅保留 MENU 节点（API 动作码为平铺根级，直接剔除） */
const menuTree = computed(() => list.value.filter((n) => n.type === 'MENU'))

const filteredTree = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return menuTree.value
  const filter = (nodes: SysPermission[]): SysPermission[] =>
    nodes.flatMap((n) => {
      const hit = (n.code || '').toLowerCase().includes(kw) || (n.name || '').toLowerCase().includes(kw)
      const children = n.children?.length ? filter(n.children) : []
      if (hit) return [{ ...n, children }]
      return children.length ? [{ ...n, children }] : []
    })
  return filter(menuTree.value)
})

/** 当前展示的菜单节点总数（含子级，随搜索联动） */
const menuCount = computed(() => {
  const count = (nodes: SysPermission[]): number =>
    nodes.reduce((sum, n) => sum + 1 + count(n.children ?? []), 0)
  return count(filteredTree.value)
})

async function load() {
  loading.value = true
  try {
    list.value = await rbacApi.permissionTree()
  } finally {
    loading.value = false
  }
}

// ── 新增 / 编辑 ──
const editVisible = ref(false)
const editFormRef = ref()
const saving = ref(false)
const editingId = ref<LongId | null>(null)
const form = ref<SysPermissionSaveRequest>({
  code: '',
  name: '',
  type: 'MENU',
  sort: 0,
  parentId: null,
  path: '',
  icon: '',
  component: '',
  hidden: 0,
  keepAlive: 0,
  externalLink: ''
})
const editRules = {
  code: [{ required: true, message: '请输入权限码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }]
}

/** 父级下拉树：排除自身及后代（编辑时防环），仅含 MENU 节点 */
const parentTreeOptions = computed(() => {
  const exclude = new Set<LongId>()
  const collect = (nodes: SysPermission[], selfId: LongId | null) => {
    for (const n of nodes) {
      if (selfId !== null && n.id === selfId) {
        exclude.add(n.id)
        const addSub = (sub: SysPermission[]) => sub.forEach((s) => {
          exclude.add(s.id)
          if (s.children?.length) addSub(s.children)
        })
        if (n.children?.length) addSub(n.children)
        continue
      }
      collect(n.children ?? [], selfId)
    }
  }
  collect(menuTree.value, editingId.value)
  const build = (nodes: SysPermission[]): Array<SysPermission & { disabled?: boolean }> =>
    nodes.map((n) => ({
      ...n,
      disabled: exclude.has(n.id),
      children: n.children?.length ? build(n.children) : undefined
    }))
  return build(menuTree.value)
})

function openCreate(parent: SysPermission | null) {
  editingId.value = null
  form.value = {
    code: '',
    name: '',
    type: 'MENU',
    sort: 0,
    parentId: parent?.id ?? null,
    path: '',
    icon: '',
    component: '',
    hidden: 0,
    keepAlive: 0,
    externalLink: ''
  }
  editVisible.value = true
}

function openEdit(row: SysPermission) {
  editingId.value = row.id
  form.value = {
    code: row.code,
    name: row.name,
    type: 'MENU',
    sort: row.sort ?? 0,
    parentId: row.parentId ?? null,
    path: row.path ?? '',
    icon: row.icon ?? '',
    component: row.component ?? '',
    hidden: row.hidden ?? 0,
    keepAlive: row.keepAlive ?? 0,
    externalLink: row.externalLink ?? ''
  }
  editVisible.value = true
}

async function submitEdit() {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload: SysPermissionSaveRequest = {
      ...form.value,
      type: 'MENU',
      path: form.value.path || null,
      icon: form.value.icon || null,
      component: form.value.component || null,
      externalLink: form.value.externalLink || null,
      hidden: form.value.hidden ?? 0,
      keepAlive: form.value.keepAlive ?? 0
    }
    if (editingId.value) {
      await rbacApi.updatePermission(editingId.value, payload)
    } else {
      await rbacApi.createPermission(payload)
    }
    ElMessage.success('保存成功')
    editVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function removeMenu(row: SysPermission) {
  try {
    await ElMessageBox.confirm(
      `确认删除菜单「${row.name}」（${row.code}）？存在子菜单或已被角色绑定时将被拒绝。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await rbacApi.deletePermission(row.id)
  ElMessage.success('删除成功')
  load()
}

/** 「更多」下拉是否至少有一项：无权限时整个下拉不渲染 */
function hasMoreActions() {
  return auth.hasPermission('permission:add') || auth.hasPermission('permission:delete')
}

function handleMoreCommand(cmd: string, row: SysPermission) {
  if (cmd === 'child') openCreate(row)
  else if (cmd === 'remove') removeMenu(row)
}

onMounted(() => load())
</script>

<style scoped>
/* 页头 / 列表卡由 design-system.css 的 List Page Template 统一提供；
   本页只保留专属单元格与弹窗样式。 */
.perm-code {
  font-family: var(--ha-font-mono);
  font-size: 12px;
  background: var(--ha-surface-hover);
  color: var(--ha-ink-secondary);
  padding: 1px 6px;
  border-radius: var(--ha-radius-sm);
}
.muted { color: var(--ha-muted); font-size: 12px; }
.icon-name {
  font-family: var(--ha-font-mono);
  font-size: 12px;
  color: var(--ha-ink-secondary);
}
.hint { margin-left: 8px; }
.tag-gap { margin-left: 4px; }
</style>
