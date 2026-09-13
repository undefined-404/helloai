<template>
  <div class="page ha-entrance-up">
    <div class="page-head">
      <h2 class="page-heading">
        用户管理
      </h2>
      <div class="page-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名/昵称"
          clearable
          class="filter-search"
          :prefix-icon="Search"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-button
          size="small"
          type="primary"
          @click="load(1)"
        >
          查询
        </el-button>
        <el-button
          size="small"
          @click="load(currentPage)"
        >
          刷新
        </el-button>
      </div>
    </div>

    <el-card class="ha-entrance-up list-card">
      <div class="table-wrapper">
        <el-table
          v-loading="loading"
          :data="list"
          border
          stripe
          style="width: 100%"
          empty-text="暂无用户"
        >
        <el-table-column
          prop="id"
          label="ID"
          width="90"
        />
        <el-table-column
          label="用户名"
          min-width="140"
        >
          <template #default="{ row }">
            <span class="user-username">{{ row.username }}</span>
            <el-tag
              v-if="row.username === 'admin'"
              size="small"
              type="warning"
              effect="plain"
              style="margin-left: 6px"
            >
              内置
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="nickname"
          label="昵称"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.nickname || '—' }}
          </template>
        </el-table-column>
        <el-table-column
          prop="email"
          label="邮箱"
          min-width="160"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.email || '—' }}
          </template>
        </el-table-column>
        <el-table-column
          label="角色"
          min-width="160"
        >
          <template #default="{ row }">
            <el-tag
              v-for="code in row.roleCodes"
              :key="code"
              size="small"
              :type="code === 'SUPER_ADMIN' ? 'danger' : 'primary'"
              effect="light"
              style="margin-right: 4px"
            >
              {{ code }}
            </el-tag>
            <span
              v-if="!row.roleCodes || row.roleCodes.length === 0"
              class="muted"
            >未分配</span>
          </template>
        </el-table-column>
        <el-table-column
          label="部门"
          min-width="140"
        >
          <template #default="{ row }">
            <el-tag
              v-for="(name, i) in row.departNames"
              :key="i"
              size="small"
              effect="plain"
              style="margin-right: 4px"
            >
              {{ name }}
            </el-tag>
            <span
              v-if="!row.departNames || row.departNames.length === 0"
              class="muted"
            >—</span>
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="90"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'ACTIVE' ? 'success' : 'info'"
              effect="light"
            >
              {{ row.status === 'ACTIVE' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="最近登录"
          width="170"
        >
          <template #default="{ row }">
            {{ fmtTime(row.lastLoginTime) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          :width="ACTION.FOUR"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-auth="'user:edit'"
              size="small"
              link
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-auth="'user:assign-role'"
              size="small"
              link
              type="primary"
              @click="openAssignRoles(row)"
            >
              分配角色
            </el-button>
            <el-button
              v-auth="'user:edit'"
              size="small"
              link
              type="primary"
              @click="openAssignOrg(row)"
            >
              组织归属
            </el-button>
            <el-button
              v-auth="'user:reset-pwd'"
              size="small"
              link
              type="warning"
              @click="openResetPassword(row)"
            >
              重置密码
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      </div>
      <el-pagination
        v-if="total > 0"
        class="list-pagination"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="load"
      />
    </el-card>

    <!-- 编辑用户 -->
    <el-dialog
      v-model="editVisible"
      title="编辑用户"
      width="460px"
      class="ha-dialog--form"
      :style="{ maxHeight: 'calc(100vh - 12vh - 16px)', minHeight: 'min(240px, calc(100vh - 12vh - 16px))', display: 'flex', flexDirection: 'column' }"
      destroy-on-close
    >
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-width="80px"
      >
        <el-form-item label="用户名">
          <el-input :model-value="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="editForm.nickname" placeholder="昵称" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editForm.email" placeholder="邮箱" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="editForm.phone" placeholder="手机号" />
        </el-form-item>
        <el-form-item
          label="状态"
          prop="status"
        >
          <el-radio-group v-model="editForm.status">
            <el-radio value="ACTIVE">
              启用
            </el-radio>
            <el-radio value="DISABLED">
              禁用
            </el-radio>
          </el-radio-group>
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

    <!-- 分配角色 -->
    <el-dialog
      v-model="assignVisible"
      :title="`分配角色：${assignTarget?.username ?? ''}`"
      width="420px"
      class="ha-dialog--scrollable"
      :style="{ maxHeight: 'calc(100vh - 10vh)' }"
      destroy-on-close
    >
      <el-checkbox-group v-model="assignRoleIds">
        <el-checkbox
          v-for="r in roleOptions"
          :key="r.id"
          :value="r.id"
        >
          {{ r.name }}
          <span class="muted">（{{ r.code }}）</span>
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="assignVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="submitAssignRoles"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 组织归属（部门，BASE-3.2） -->
    <el-dialog
      v-model="orgVisible"
      :title="`组织归属：${orgTarget?.username ?? ''}`"
      width="520px"
      class="ha-dialog--scrollable"
      :style="{ maxHeight: 'calc(100vh - 10vh)' }"
      destroy-on-close
    >
      <div class="org-section">
        <div class="org-label">
          部门
        </div>
        <el-tree-select
          v-model="orgDepartIds"
          :data="departOptions"
          :props="{ label: 'name', value: 'id' }"
          multiple
          check-strictly
          clearable
          show-checkbox
          placeholder="选择部门（可多选）"
          style="width: 100%"
        />
      </div>
      <template #footer>
        <el-button @click="orgVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="submitAssignOrg"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog
      v-model="passwordVisible"
      :title="`重置密码：${passwordTarget?.username ?? ''}`"
      width="420px"
      class="ha-dialog--form"
      :style="{ maxHeight: 'calc(100vh - 12vh - 16px)', minHeight: 'min(240px, calc(100vh - 12vh - 16px))', display: 'flex', flexDirection: 'column' }"
      destroy-on-close
    >
      <el-form
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-width="80px"
      >
        <el-form-item
          label="新密码"
          prop="newPassword"
        >
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            placeholder="6-64 位新密码"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="submitResetPassword"
        >
          确认重置
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { rbacApi } from '@/api/rbac'
import type { SysDepart, SysRole, SysUserItem } from '@/types'
import type { LongId } from '@/types'
import { fmtTime, ACTION } from '@/utils/tableConfig'

const list = ref<SysUserItem[]>([])
const total = ref(0)
const pageSize = ref(10)
const currentPage = ref(1)
const loading = ref(false)
const keyword = ref('')

async function load(page = 1) {
  loading.value = true
  currentPage.value = page
  try {
    const res = await rbacApi.userPage({ page, size: pageSize.value, keyword: keyword.value.trim() || undefined })
    list.value = res.records || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

// ── 编辑用户 ──
const editVisible = ref(false)
const editFormRef = ref()
const saving = ref(false)
const editForm = ref({ username: '', nickname: '', email: '', phone: '', status: 'ACTIVE' })
const editTarget = ref<SysUserItem | null>(null)
const editRules = {
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

function openEdit(row: SysUserItem) {
  editTarget.value = row
  editForm.value = {
    username: row.username,
    nickname: row.nickname ?? '',
    email: row.email ?? '',
    phone: row.phone ?? '',
    status: row.status || 'ACTIVE'
  }
  editVisible.value = true
}

async function submitEdit() {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid || !editTarget.value) return
  saving.value = true
  try {
    await rbacApi.updateUser(editTarget.value.id, {
      nickname: editForm.value.nickname || null,
      email: editForm.value.email || null,
      phone: editForm.value.phone || null,
      status: editForm.value.status
    })
    ElMessage.success('保存成功')
    editVisible.value = false
    load(currentPage.value)
  } finally {
    saving.value = false
  }
}

// ── 分配角色 ──
const assignVisible = ref(false)
const assignTarget = ref<SysUserItem | null>(null)
const assignRoleIds = ref<Array<LongId>>([])
const roleOptions = ref<SysRole[]>([])

async function openAssignRoles(row: SysUserItem) {
  assignTarget.value = row
  if (roleOptions.value.length === 0) {
    roleOptions.value = await rbacApi.roles()
  }
  const ids = await rbacApi.userRoleIds(row.id)
  assignRoleIds.value = ids
  assignVisible.value = true
}

async function submitAssignRoles() {
  if (!assignTarget.value) return
  saving.value = true
  try {
    await rbacApi.assignUserRoles(assignTarget.value.id, assignRoleIds.value)
    ElMessage.success('角色已更新')
    assignVisible.value = false
    load(currentPage.value)
  } finally {
    saving.value = false
  }
}

// ── 组织归属（部门，BASE-3.2）──
const orgVisible = ref(false)
const orgTarget = ref<SysUserItem | null>(null)
const orgDepartIds = ref<Array<LongId>>([])
const departOptions = ref<SysDepart[]>([])

async function openAssignOrg(row: SysUserItem) {
  orgTarget.value = row
  if (departOptions.value.length === 0) {
    departOptions.value = await rbacApi.departTree()
  }
  orgDepartIds.value = await rbacApi.userDepartIds(row.id)
  orgVisible.value = true
}

async function submitAssignOrg() {
  if (!orgTarget.value) return
  saving.value = true
  try {
    await rbacApi.assignUserDeparts(orgTarget.value.id, orgDepartIds.value)
    ElMessage.success('组织归属已更新')
    orgVisible.value = false
    load(currentPage.value)
  } finally {
    saving.value = false
  }
}

// ── 重置密码 ──
const passwordVisible = ref(false)
const passwordTarget = ref<SysUserItem | null>(null)
const passwordFormRef = ref()
const passwordForm = ref({ newPassword: '' })
const passwordRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '长度需在 6-64 位之间', trigger: 'blur' }
  ]
}

function openResetPassword(row: SysUserItem) {
  passwordTarget.value = row
  passwordForm.value = { newPassword: '' }
  passwordVisible.value = true
}

async function submitResetPassword() {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid || !passwordTarget.value) return
  saving.value = true
  try {
    await rbacApi.resetUserPassword(passwordTarget.value.id, passwordForm.value.newPassword)
    ElMessage.success('密码已重置')
    passwordVisible.value = false
  } finally {
    saving.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
/* Page wrapper: 与外层 .app-content (overflow-y: auto) 协作，
   用 min-height: 100% 让页面占满 .app-content 的可视区，
   然后用 flex 列向子级分配高度，确保数据少时 el-card 也能拉伸到底。 */
.page {
  max-width: var(--ha-content-width);
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
  flex-wrap: wrap;
  flex-shrink: 0;
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

/* 列表卡片：flex:1 吸收 .page 剩余高度，让数据少时卡片仍撑到底；
    display:flex 让内部 el-table 也能按卡片高度排版 */
.list-card {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.list-card :deep(.el-card__body) {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
/* 表格容器：吸收卡片内除分页外的全部高度，
   让 el-table 即使只有 1~2 行也能撑满卡片底部；空数据时由 el-empty 居中 */
.table-wrapper {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
}
.table-wrapper :deep(.el-table) {
  flex: 1 1 auto;
  min-height: 0;
}
/* 分页：贴底部、居中；空数据时不渲染，避免无谓占用空间 */
.list-pagination {
  margin-top: 16px;
  justify-content: center;
  flex-shrink: 0;
}

.user-username { font-weight: 600; color: var(--ha-ink); }
.muted { color: var(--ha-muted); font-size: 12px; }
.org-section { margin-bottom: 16px; }
.org-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-primary);
  margin-bottom: 8px;
}
</style>
