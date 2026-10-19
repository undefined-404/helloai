<template>
  <div class="page list-page list-page--fill ha-entrance-up">
    <!-- 页头：图标块 + 标题（含条数）+ 副标题，右侧操作区 -->
    <div class="page-head">
      <div class="page-head-title">
        <div class="dash-icon-block primary">
          <el-icon><User /></el-icon>
        </div>
        <div>
          <h2 class="page-heading">
            用户管理
            <span
              v-if="total"
              class="query-stats"
            >共 {{ total }} 条</span>
          </h2>
          <p class="page-subheading">
            账号、角色与组织归属的统一维护入口
          </p>
        </div>
      </div>
      <div class="page-head-actions">
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
        <el-button
          v-auth="'user:add'"
          size="small"
          type="primary"
          @click="openCreate"
        >
          新增用户
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
            label="用户"
            min-width="170"
          >
            <template #default="{ row }">
              <!-- 用户名 + 内置标记为主行，19 位 ID 作副行：比单独一列 ID 省 150px，
                   也不会因为列宽不足把 ID 竖排成 5 行 -->
              <div class="user-cell">
                <span class="user-name-row">
                  <span class="user-username">{{ row.username }}</span>
                  <el-tag
                    v-if="row.username === 'admin'"
                    size="small"
                    type="warning"
                    effect="plain"
                  >
                    内置
                  </el-tag>
                </span>
                <span class="user-id">{{ row.id }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            prop="nickname"
            label="昵称"
            min-width="100"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ row.nickname || '—' }}
            </template>
          </el-table-column>
          <el-table-column
            prop="email"
            label="邮箱"
            min-width="140"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ row.email || '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="角色"
            min-width="150"
          >
            <template #default="{ row }">
              <!-- 多个角色必须包在可换行的 flex 里：相邻 inline 标签之间没有空白，
                   浏览器找不到断行点，SUPER_ADMIN + 第二个角色会被当成一条长串硬裁。 -->
              <div
                v-if="row.roleCodes && row.roleCodes.length"
                class="cell-tags"
              >
                <el-tag
                  v-for="code in row.roleCodes"
                  :key="code"
                  size="small"
                  :type="code === 'SUPER_ADMIN' ? 'danger' : 'primary'"
                  effect="light"
                >
                  {{ code }}
                </el-tag>
              </div>
              <span
                v-else
                class="muted"
              >未分配</span>
            </template>
          </el-table-column>
          <el-table-column
            label="部门"
            min-width="104"
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
            width="132"
          >
            <template #default="{ row }">
              <div class="time-cell">
                <span>{{ timeParts(row.lastLoginTime).date }}</span>
                <span class="time-sub">{{ timeParts(row.lastLoginTime).time }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="172"
            fixed="right"
          >
            <template #default="{ row }">
              <div class="action-cell">
                <el-button
                  v-auth="'user:edit'"
                  size="small"
                  @click="openEdit(row)"
                >
                  编辑
                </el-button>
                <!-- 与子任务列表同一布局：主操作外置，其余收进「更多」下拉 -->
                <el-dropdown
                  v-if="hasMoreActions()"
                  trigger="click"
                  @command="(cmd: string) => handleMoreCommand(cmd, row)"
                >
                  <el-button
                    size="small"
                    :aria-label="`用户「${row.username}」更多操作`"
                  >
                    更多
                    <el-icon class="el-icon--right">
                      <ArrowDown />
                    </el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        v-if="auth.hasPermission('user:assign-role')"
                        command="roles"
                      >
                        分配角色
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="auth.hasPermission('user:edit')"
                        command="org"
                      >
                        组织归属
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="auth.hasPermission('user:reset-pwd')"
                        command="password"
                      >
                        <span class="dropdown-danger">重置密码</span>
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
        v-if="total > 0"
        class="list-pager"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :current-page="currentPage"
        @current-change="load"
        @size-change="onSizeChange"
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
          <el-input
            :model-value="editForm.username"
            disabled
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input
            v-model="editForm.nickname"
            placeholder="昵称"
          />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input
            v-model="editForm.email"
            placeholder="邮箱"
          />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input
            v-model="editForm.phone"
            placeholder="手机号"
          />
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

    <!-- 新增用户（BASE-4.5：建号即签发角色） -->
    <el-dialog
      v-model="createVisible"
      title="新增用户"
      width="460px"
      class="ha-dialog--form"
      :style="{ maxHeight: 'calc(100vh - 12vh - 16px)', minHeight: 'min(240px, calc(100vh - 12vh - 16px))', display: 'flex', flexDirection: 'column' }"
      destroy-on-close
    >
      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="80px"
      >
        <el-form-item
          label="用户名"
          prop="username"
        >
          <el-input
            v-model="createForm.username"
            placeholder="登录用户名，2-64 位"
          />
        </el-form-item>
        <el-form-item
          label="初始密码"
          prop="password"
        >
          <el-input
            v-model="createForm.password"
            type="password"
            show-password
            placeholder="6-64 位"
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input
            v-model="createForm.nickname"
            placeholder="昵称（可空）"
          />
        </el-form-item>
        <el-form-item
          label="角色"
          prop="roleCode"
        >
          <el-select
            v-model="createForm.roleCode"
            placeholder="选择初始角色"
            style="width: 100%"
          >
            <el-option
              v-for="r in roleOptions"
              :key="r.code"
              :label="`${r.name}（${r.code}）`"
              :value="r.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="createForm.remark"
            placeholder="备注（可空）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="submitCreate"
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
import { Search, ArrowDown, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { rbacApi } from '@/api/rbac'
import { useAuthStore } from '@/stores/auth'
import type { SysDepart, SysRole, SysUserItem } from '@/types'
import type { LongId } from '@/types'
import { splitDateTime } from '@/utils/tableConfig'

const list = ref<SysUserItem[]>([])
const total = ref(0)
const pageSize = ref(10)
const currentPage = ref(1)
const loading = ref(false)
const keyword = ref('')

/** 时间拆成日期/时间两行（窄列展示，与子任务列表同构） */
function timeParts(t: string | null | undefined) {
  return splitDateTime(t) ?? { date: '-', time: '' }
}
// 下拉项不能用 v-auth（ElDropdownItem 根节点是 fragment，指令会被静默忽略），
// 与子任务列表同一处理：改用 v-if + hasPermission 过滤。
const auth = useAuthStore()

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

/** 切每页条数：回到第一页，避免新 pageSize 下页码越界 */
function onSizeChange(size: number) {
  pageSize.value = size
  load(1)
}

/**
 * 校验表单，失败时给出可见反馈。
 *
 * <p>2026-10-19 修复：Element Plus 的 `validate()` 在校验失败时是 **reject**（不是
 * resolve(false)）。原写法 `.catch(() => false)` 之后直接 `return`，等于完全静默——
 * 用户只看到「点保存没反应、F12 里一个请求都没有」，误判成按钮坏了。
 * 现在统一在 catch 里弹一条提示，把「哪里没填」说出来。</p>
 */
async function validateOrWarn(formRef: FormInstance | undefined, hint: string): Promise<boolean> {
  if (!formRef) return false
  try {
    await formRef.validate()
    return true
  } catch {
    ElMessage.warning(hint)
    return false
  }
}

/** 该行是否有「更多」里的操作可展示（无权限时不渲染下拉，避免空菜单） */
function hasMoreActions(): boolean {
  return auth.hasPermission('user:assign-role')
    || auth.hasPermission('user:edit')
    || auth.hasPermission('user:reset-pwd')
}

function handleMoreCommand(cmd: string, row: SysUserItem) {
  if (cmd === 'roles') openAssignRoles(row)
  else if (cmd === 'org') openAssignOrg(row)
  else if (cmd === 'password') openResetPassword(row)
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
  const valid = await validateOrWarn(editFormRef.value, '请先选择用户状态')
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

// ── 新增用户（BASE-4.5：建号即签发角色） ──
const createVisible = ref(false)
const createFormRef = ref()
const createForm = ref({ username: '', password: '', nickname: '', roleCode: 'NORMAL_USER', remark: '' })
const createRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 64, message: '长度需在 2-64 位之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, max: 64, message: '长度需在 6-64 位之间', trigger: 'blur' }
  ],
  roleCode: [{ required: true, message: '请选择初始角色', trigger: 'change' }]
}

async function openCreate() {
  createForm.value = { username: '', password: '', nickname: '', roleCode: 'NORMAL_USER', remark: '' }
  if (roleOptions.value.length === 0) {
    roleOptions.value = await rbacApi.roles()
  }
  createVisible.value = true
  createFormRef.value?.clearValidate()
}

async function submitCreate() {
  const valid = await validateOrWarn(
    createFormRef.value,
    '请先补全必填项：用户名（2-64 位）、初始密码（6-64 位）、初始角色'
  )
  if (!valid) return
  // 再兜一层：trim 后为空说明只敲了空格，表单 min 校验能过但后端会拒
  if (!createForm.value.username.trim()) {
    ElMessage.warning('用户名不能为空白')
    return
  }
  saving.value = true
  try {
    await rbacApi.createUser({
      username: createForm.value.username.trim(),
      password: createForm.value.password,
      nickname: createForm.value.nickname || undefined,
      roleCode: createForm.value.roleCode,
      remark: createForm.value.remark || undefined
    })
    ElMessage.success('用户已创建')
    createVisible.value = false
    load(1)
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
  const valid = await validateOrWarn(passwordFormRef.value, '请输入 6-64 位的新密码')
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
/* 页头 / 列表卡 / 分页 / 筛选控件由 design-system.css 的 List Page Template 统一提供；
   本页只保留专属单元格与弹窗样式。 */
.user-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.user-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.user-username { font-weight: 600; color: var(--ha-ink); }
.user-id {
  font-family: var(--ha-font-mono);
  font-size: 12px;
  color: var(--ha-muted);
}
/* 时间两行（日期/时分秒）由 design-system.css 的 .time-cell / .time-sub 统一提供 */
/* 危险操作在下拉里的着色：与子任务列表同一约定 */
:deep(.dropdown-danger) { color: var(--el-color-danger); }
.muted { color: var(--ha-muted); font-size: 12px; }
.org-section { margin-bottom: 16px; }
.org-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-primary);
  margin-bottom: 8px;
}
</style>
