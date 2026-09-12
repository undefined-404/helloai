<template>
  <div class="page ha-entrance-up">
    <div class="page-head">
      <h2 class="page-heading">
        菜单管理
      </h2>
      <div class="page-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索菜单名/权限码"
          clearable
          class="filter-search"
          :prefix-icon="Search"
        />
        <el-button
          v-auth="'permission:add'"
          size="small"
          type="primary"
          @click="openCreate(null)"
        >
          <el-icon><Plus /></el-icon>
          <span>新增菜单</span>
        </el-button>
        <el-button
          size="small"
          @click="load"
        >
          刷新
        </el-button>
      </div>
    </div>

    <el-card class="ha-entrance-up">
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
          min-width="180"
        />
        <el-table-column
          prop="code"
          label="权限码"
          min-width="180"
        >
          <template #default="{ row }">
            <code class="perm-code">{{ row.code }}</code>
          </template>
        </el-table-column>
        <el-table-column
          prop="path"
          label="路由路径"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <code v-if="row.path" class="perm-code">{{ row.path }}</code>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="component"
          label="组件"
          min-width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span v-if="row.component">{{ row.component }}</span>
            <span v-else class="muted">—（目录/聚合父，跳转首个可见子）</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="icon"
          label="图标"
          width="110"
        >
          <template #default="{ row }">
            <span v-if="row.icon">{{ row.icon }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="sort"
          label="排序"
          width="70"
        />
        <el-table-column
          label="渲染"
          width="130"
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
        <el-table-column
          label="操作"
          :width="ACTION.THREE"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-auth="'permission:add'"
              size="small"
              link
              type="primary"
              @click="openCreate(row)"
            >
              新增子菜单
            </el-button>
            <el-button
              v-auth="'permission:edit'"
              size="small"
              link
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-auth="'permission:delete'"
              size="small"
              link
              type="danger"
              @click="removeMenu(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
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
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rbacApi } from '@/api/rbac'
import type { SysPermission, SysPermissionSaveRequest, LongId } from '@/types'
import { ACTION } from '@/utils/tableConfig'

const list = ref<SysPermission[]>([])
const loading = ref(false)
const keyword = ref('')

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

onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
  flex-wrap: wrap;
}
.page-heading {
  font-size: 20px;
  font-weight: 600;
  color: var(--ha-primary);
  letter-spacing: -0.02em;
  margin: 0;
}
.page-actions { display: flex; align-items: center; gap: 8px; }
.filter-search { width: 260px; }
.perm-code {
  font-family: var(--ha-font-mono);
  font-size: 12px;
  color: var(--ha-primary);
  background: var(--ha-surface-hover);
  padding: 1px 6px;
  border-radius: 4px;
}
.muted { color: var(--ha-muted); font-size: 12px; }
.hint { margin-left: 8px; }
.tag-gap { margin-left: 4px; }
</style>
