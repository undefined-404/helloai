<template>
  <div class="page list-page list-page--fill ha-entrance-up">
    <!-- 页头：图标块 + 标题（含条数）+ 副标题，右侧操作区 -->
    <div class="page-head">
      <div class="page-head-title">
        <div class="dash-icon-block primary">
          <el-icon><Lock /></el-icon>
        </div>
        <div>
          <h2 class="page-heading">
            权限管理
            <span
              v-if="filteredList.length"
              class="query-stats"
            >共 {{ filteredList.length }} 条</span>
          </h2>
          <p class="page-subheading">
            接口动作权限码与行级数据规则的维护入口
          </p>
        </div>
      </div>
      <div class="page-head-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索权限码/名称"
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
          @click="openCreate"
        >
          <el-icon><Plus /></el-icon>
          <span>新增接口</span>
        </el-button>
      </div>
    </div>

    <el-card class="ha-entrance-up list-card">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="page-tip"
        title="本页维护接口动作级权限码（type=API，如 user:add）。菜单/目录的维护见「菜单管理」。"
      />
      <div class="table-wrapper">
        <el-table
          v-loading="loading"
          :data="pagedList"
          row-key="id"
          border
          stripe
          style="width: 100%"
          empty-text="暂无接口权限码"
        >
          <el-table-column
            prop="name"
            label="名称"
            min-width="200"
          />
          <el-table-column
            prop="code"
            label="权限码"
            min-width="220"
          >
            <template #default="{ row }">
              <code class="perm-code">{{ row.code }}</code>
            </template>
          </el-table-column>
          <el-table-column
            prop="sort"
            label="排序"
            width="90"
          />
          <el-table-column
            label="操作"
            width="172"
            fixed="right"
          >
            <template #default="{ row }">
              <div class="action-cell">
                <!-- 与菜单管理/用户管理同一布局：主操作外置，次级与危险操作收进「更多」 -->
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
                    :aria-label="`权限「${row.name}」更多操作`"
                  >
                    更多
                    <el-icon class="el-icon--right">
                      <ArrowDown />
                    </el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        v-if="auth.hasPermission('permission:edit')"
                        command="rule"
                      >
                        数据规则
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
      <el-pagination
        v-if="filteredList.length > 0"
        class="list-pager"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="filteredList.length"
        :page-size="pageSize"
        :page-sizes="[20, 50, 100]"
        :current-page="currentPage"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!-- 新增 / 编辑 接口权限码 -->
    <el-dialog
      v-model="editVisible"
      :title="editingId ? '编辑接口权限' : '新增接口权限'"
      width="480px"
      class="ha-dialog--form"
      :style="{ maxHeight: 'calc(100vh - 12vh - 16px)', minHeight: 'min(240px, calc(100vh - 12vh - 16px))', display: 'flex', flexDirection: 'column' }"
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
            placeholder="如 user:add"
            :disabled="!!editingId"
          />
        </el-form-item>
        <el-form-item
          label="名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            placeholder="如 用户-新增"
          />
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

    <!-- 数据规则（BASE-3.3，受控枚举） -->
    <el-dialog
      v-model="ruleVisible"
      :title="`数据规则：${ruleTarget?.name ?? ''}`"
      width="540px"
      class="ha-dialog--form"
      :style="{ maxHeight: 'calc(100vh - 12vh - 16px)', minHeight: 'min(240px, calc(100vh - 12vh - 16px))', display: 'flex', flexDirection: 'column' }"
      destroy-on-close
    >
      <el-form label-width="90px">
        <el-form-item label="规则类型">
          <el-radio-group v-model="ruleForm.ruleType">
            <el-radio value="ALL">
              全部数据
            </el-radio>
            <el-radio value="DEPT">
              本部门
            </el-radio>
            <el-radio value="DEPT_AND_CHILD">
              本部门及下级
            </el-radio>
            <el-radio value="CUSTOM">
              自定义部门
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item
          v-if="ruleForm.ruleType === 'CUSTOM'"
          label="部门范围"
        >
          <el-tree-select
            v-model="ruleDepartIds"
            :data="departOptions"
            :props="{ label: 'name', value: 'id' }"
            multiple
            check-strictly
            show-checkbox
            clearable
            placeholder="选择部门（可多选）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="说明">
          <span class="muted">规则作用于该权限码的数据可见范围（如 user:view → 用户列表）；「全部数据」表示不限制，且不写入规则。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="submitDataRule"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// ============================================================
// 权限管理（BASE-2.2 + BASE-3.3 收口）：仅维护 type=API 的接口动作级权限码
// - 数据源：/api/admin/permissions（平铺列表，过滤 type=API）
// - 菜单/目录维护见「菜单管理」页（MenuList.vue）
// - 数据规则（BASE-3.3）：配置该权限码的行级数据可见范围（受控枚举）
// ============================================================
import { ref, computed, watch, onMounted } from 'vue'
import { Plus, Search, Lock, ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rbacApi } from '@/api/rbac'
import { useAuthStore } from '@/stores/auth'
import type { SysDepart, SysPermission, SysPermissionSaveRequest, LongId } from '@/types'

const list = ref<SysPermission[]>([])
const loading = ref(false)
const keyword = ref('')
// 下拉项不能用 v-auth（ElDropdownItem 根节点是 fragment）
const auth = useAuthStore()

/** 仅保留 API 动作码（MENU 为菜单/目录，在「菜单管理」页维护） */
const apiList = computed(() => list.value.filter((n) => n.type === 'API'))

const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return apiList.value
  return apiList.value.filter(
    (n) => (n.code || '').toLowerCase().includes(kw) || (n.name || '').toLowerCase().includes(kw)
  )
})

// 前端分页：接口权限码近百条，全量渲染会让卡片拉到数千像素高
const currentPage = ref(1)
const pageSize = ref(20)
const pagedList = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredList.value.slice(start, start + pageSize.value)
})
watch(keyword, () => { currentPage.value = 1 })
function onPageChange(page: number) { currentPage.value = page }
function onSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
}

async function load() {
  loading.value = true
  try {
    list.value = await rbacApi.permissions()
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
  type: 'API',
  sort: 0,
  parentId: null,
  path: null,
  icon: null,
  component: null,
  hidden: 0,
  keepAlive: 0,
  externalLink: null
})
const editRules = {
  code: [{ required: true, message: '请输入权限码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}

function openCreate() {
  editingId.value = null
  form.value = {
    code: '',
    name: '',
    type: 'API',
    sort: 0,
    parentId: null,
    path: null,
    icon: null,
    component: null,
    hidden: 0,
    keepAlive: 0,
    externalLink: null
  }
  editVisible.value = true
}

function openEdit(row: SysPermission) {
  editingId.value = row.id
  form.value = {
    code: row.code,
    name: row.name,
    type: 'API',
    sort: row.sort ?? 0,
    parentId: null,
    path: null,
    icon: null,
    component: null,
    hidden: 0,
    keepAlive: 0,
    externalLink: null
  }
  editVisible.value = true
}

async function submitEdit() {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    // type=API：无层级/渲染字段，统一置空（后端只按非 null 字段更新）
    const payload: SysPermissionSaveRequest = {
      code: form.value.code,
      name: form.value.name,
      type: 'API',
      sort: form.value.sort ?? 0,
      parentId: null,
      path: null,
      icon: null,
      component: null,
      hidden: 0,
      keepAlive: 0,
      externalLink: null
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

async function removePermission(row: SysPermission) {
  try {
    await ElMessageBox.confirm(
      `确认删除权限码「${row.name}」（${row.code}）？已被角色绑定时将被拒绝。`,
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
  return auth.hasPermission('permission:edit') || auth.hasPermission('permission:delete')
}

function handleMoreCommand(cmd: string, row: SysPermission) {
  if (cmd === 'rule') openDataRule(row)
  else if (cmd === 'remove') removePermission(row)
}

// ── 数据规则（BASE-3.3，受控枚举）──
const ruleVisible = ref(false)
const ruleTarget = ref<SysPermission | null>(null)
const ruleForm = ref<{ ruleType: string; ruleValue: string | null }>({ ruleType: 'ALL', ruleValue: null })
const ruleDepartIds = ref<Array<LongId>>([])
const departOptions = ref<SysDepart[]>([])

async function openDataRule(row: SysPermission) {
  ruleTarget.value = row
  if (departOptions.value.length === 0) {
    departOptions.value = await rbacApi.departTree()
  }
  const rule = await rbacApi.dataRule(row.id)
  ruleForm.value = { ruleType: rule.ruleType || 'ALL', ruleValue: rule.ruleValue ?? null }
  ruleDepartIds.value = rule.ruleValue
    ? rule.ruleValue.split(',').map((s) => s.trim()).filter(Boolean)
    : []
  ruleVisible.value = true
}

async function submitDataRule() {
  if (!ruleTarget.value) return
  saving.value = true
  try {
    const ruleValue = ruleForm.value.ruleType === 'CUSTOM' ? ruleDepartIds.value.join(',') : null
    await rbacApi.saveDataRule(ruleTarget.value.id, { ruleType: ruleForm.value.ruleType, ruleValue })
    ElMessage.success('数据规则已保存')
    ruleVisible.value = false
  } finally {
    saving.value = false
  }
}

onMounted(() => load())
</script>

<style scoped>
/* 页头 / 列表卡由 design-system.css 的 List Page Template 统一提供；
   本页只保留卡内说明条与专属单元格样式。 */
.page-tip { margin-bottom: 12px; flex-shrink: 0; }
.perm-code {
  font-family: var(--ha-font-mono);
  font-size: 12px;
  background: var(--ha-surface-hover);
  color: var(--ha-ink-secondary);
  padding: 1px 6px;
  border-radius: var(--ha-radius-sm);
}
.muted { color: var(--ha-muted); font-size: 12px; }
</style>
