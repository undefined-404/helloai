<template>
  <div class="page ha-entrance-up">
    <div class="page-head">
      <h2 class="page-heading">
        角色管理
      </h2>
      <div class="page-actions">
        <el-button
          v-auth="'role:add'"
          size="small"
          type="primary"
          @click="openCreate"
        >
          <el-icon><Plus /></el-icon>
          <span>新建角色</span>
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
        :data="list"
        border
        stripe
        style="width: 100%"
        empty-text="暂无角色"
      >
        <el-table-column
          label="角色编码"
          min-width="140"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.code === 'SUPER_ADMIN' ? 'danger' : 'primary'"
              effect="light"
            >
              {{ row.code }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="name"
          label="角色名称"
          min-width="120"
        />
        <el-table-column
          prop="description"
          label="描述"
          min-width="200"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.description || '—' }}
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
          prop="sort"
          label="排序"
          width="70"
        />
        <el-table-column
          label="操作"
          :width="ACTION.THREE"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-auth="'role:edit'"
              size="small"
              link
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-auth="'role:assign-perm'"
              size="small"
              link
              type="primary"
              @click="openAssignPermissions(row)"
            >
              分配权限
            </el-button>
            <el-button
              v-auth="'role:delete'"
              size="small"
              link
              type="danger"
              :disabled="row.code === 'SUPER_ADMIN'"
              @click="removeRole(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 编辑角色 -->
    <el-dialog
      v-model="editVisible"
      :title="editingId ? '编辑角色' : '新建角色'"
      width="480px"
      class="ha-dialog--form"
      :style="{ height: 'calc(100vh - 12vh - 32px)', minHeight: '420px', display: 'flex', flexDirection: 'column' }"
      destroy-on-close
    >
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-width="80px"
      >
        <el-form-item
          label="角色编码"
          prop="code"
        >
          <el-input
            v-model="editForm.code"
            placeholder="如 OPERATOR"
            :disabled="!!editingId"
          />
        </el-form-item>
        <el-form-item
          label="角色名称"
          prop="name"
        >
          <el-input
            v-model="editForm.name"
            placeholder="角色名称"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="2"
            placeholder="角色描述"
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
        <el-form-item label="排序">
          <el-input-number
            v-model="editForm.sort"
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

    <!-- 分配权限 -->
    <el-dialog
      v-model="permVisible"
      :title="`分配权限：${permTarget?.name ?? ''}`"
      width="560px"
      class="ha-dialog--scrollable"
      :style="{ maxHeight: 'calc(100vh - 10vh)' }"
      destroy-on-close
    >
      <div
        v-for="group in permissionGroups"
        :key="group.label"
        class="perm-group"
      >
        <div class="perm-group-label">
          {{ group.label }}
        </div>
        <el-checkbox-group
          v-model="permCheckedIds"
          class="perm-checkboxes"
        >
          <el-checkbox
            v-for="p in group.items"
            :key="p.id"
            :value="p.id"
          >
            {{ p.name }}
            <span class="muted">（{{ p.code }}）</span>
          </el-checkbox>
        </el-checkbox-group>
      </div>
      <template #footer>
        <el-button @click="permVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="submitAssignPermissions"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rbacApi } from '@/api/rbac'
import type { SysPermission, SysRole } from '@/types'
import type { LongId } from '@/types'
import { ACTION } from '@/utils/tableConfig'

const list = ref<SysRole[]>([])
const loading = ref(false)
const allPermissions = ref<SysPermission[]>([])

async function load() {
  loading.value = true
  try {
    list.value = await rbacApi.roles()
  } finally {
    loading.value = false
  }
}

// ── 新建 / 编辑 ──
const editVisible = ref(false)
const editFormRef = ref()
const saving = ref(false)
const editingId = ref<LongId | null>(null)
const editForm = ref({ code: '', name: '', description: '', status: 'ACTIVE', sort: 0 })
const editRules = {
  code: [{ required: true, message: '请输入角色编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

function openCreate() {
  editingId.value = null
  editForm.value = { code: '', name: '', description: '', status: 'ACTIVE', sort: 0 }
  editVisible.value = true
}

function openEdit(row: SysRole) {
  editingId.value = row.id
  editForm.value = {
    code: row.code,
    name: row.name,
    description: row.description ?? '',
    status: row.status || 'ACTIVE',
    sort: row.sort ?? 0
  }
  editVisible.value = true
}

async function submitEdit() {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingId.value) {
      await rbacApi.updateRole(editingId.value, {
        name: editForm.value.name,
        description: editForm.value.description || null,
        status: editForm.value.status,
        sort: editForm.value.sort
      })
    } else {
      await rbacApi.createRole({
        code: editForm.value.code,
        name: editForm.value.name,
        description: editForm.value.description || null,
        status: editForm.value.status,
        sort: editForm.value.sort
      })
    }
    ElMessage.success('保存成功')
    editVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function removeRole(row: SysRole) {
  try {
    await ElMessageBox.confirm(`确认删除角色「${row.name}」？删除后该角色的权限绑定将一并移除。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await rbacApi.deleteRole(row.id)
  ElMessage.success('删除成功')
  load()
}

// ── 分配权限 ──
const permVisible = ref(false)
const permTarget = ref<SysRole | null>(null)
const permCheckedIds = ref<Array<LongId>>([])

const permissionGroups = computed(() => {
  const items = allPermissions.value
  const menu = items.filter(p => p.type === 'MENU')
  const api = items.filter(p => p.type !== 'MENU')
  const groups: Array<{ label: string; items: SysPermission[] }> = []
  if (menu.length > 0) groups.push({ label: '菜单权限', items: menu })
  if (api.length > 0) groups.push({ label: '接口权限', items: api })
  return groups
})

async function openAssignPermissions(row: SysRole) {
  permTarget.value = row
  if (allPermissions.value.length === 0) {
    allPermissions.value = await rbacApi.permissions()
  }
  permCheckedIds.value = await rbacApi.rolePermissionIds(row.id)
  permVisible.value = true
}

async function submitAssignPermissions() {
  if (!permTarget.value) return
  saving.value = true
  try {
    await rbacApi.assignRolePermissions(permTarget.value.id, permCheckedIds.value)
    ElMessage.success('权限已更新')
    permVisible.value = false
  } finally {
    saving.value = false
  }
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
.muted { color: var(--ha-muted); font-size: 12px; }
.perm-group { margin-bottom: 16px; }
.perm-group-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-primary);
  margin-bottom: 8px;
}
.perm-checkboxes { display: flex; flex-direction: column; gap: 6px; }
</style>
