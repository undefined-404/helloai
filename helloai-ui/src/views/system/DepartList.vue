<template>
  <div class="page list-page list-page--fill ha-entrance-up">
    <!-- 页头：图标块 + 标题（含条数）+ 副标题，右侧操作区 -->
    <div class="page-head">
      <div class="page-head-title">
        <div class="dash-icon-block primary">
          <el-icon><OfficeBuilding /></el-icon>
        </div>
        <div>
          <h2 class="page-heading">
            部门管理
            <span
              v-if="departCount"
              class="query-stats"
            >共 {{ departCount }} 条</span>
          </h2>
          <p class="page-subheading">
            组织树层级与部门状态的统一维护
          </p>
        </div>
      </div>
      <div class="page-head-actions">
        <el-button
          size="small"
          @click="load"
        >
          刷新
        </el-button>
        <el-button
          v-auth="'depart:add'"
          size="small"
          type="primary"
          @click="openCreate(null)"
        >
          <el-icon><Plus /></el-icon>
          <span>新增部门</span>
        </el-button>
      </div>
    </div>

    <el-card class="ha-entrance-up list-card">
      <div class="table-wrapper">
        <el-table
          v-loading="loading"
          :data="list"
          row-key="id"
          border
          stripe
          default-expand-all
          style="width: 100%"
          empty-text="暂无部门"
        >
          <el-table-column
            prop="name"
            label="部门名称"
            min-width="220"
          />
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
            prop="description"
            label="描述"
            min-width="180"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ row.description || '—' }}
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="172"
            fixed="right"
          >
            <template #default="{ row }">
              <div class="action-cell">
                <!-- 与菜单/权限/用户管理同一布局：主操作外置，次级与危险操作收进「更多」 -->
                <el-button
                  v-auth="'depart:edit'"
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
                    :aria-label="`部门「${row.name}」更多操作`"
                  >
                    更多
                    <el-icon class="el-icon--right">
                      <ArrowDown />
                    </el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        v-if="auth.hasPermission('depart:add')"
                        command="child"
                      >
                        新增子部门
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="auth.hasPermission('depart:delete')"
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

    <!-- 新增 / 编辑 部门 -->
    <el-dialog
      v-model="editVisible"
      :title="editingId ? '编辑部门' : '新增部门'"
      width="480px"
      class="ha-dialog--form"
      :style="{ maxHeight: 'calc(100vh - 12vh - 16px)', minHeight: 'min(240px, calc(100vh - 12vh - 16px))', display: 'flex', flexDirection: 'column' }"
      destroy-on-close
    >
      <el-form
        ref="editFormRef"
        :model="form"
        :rules="rules"
        label-width="80px"
      >
        <el-form-item
          label="部门名称"
          prop="name"
        >
          <el-input
            v-model="form.name"
            placeholder="如 研发中心"
          />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            :props="{ label: 'name', value: 'id' }"
            check-strictly
            clearable
            placeholder="留空为顶级部门"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
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
            v-model="form.sort"
            :min="0"
            :max="999"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="部门描述"
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
import { ref, computed, onMounted } from 'vue'
import { Plus, OfficeBuilding, ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rbacApi } from '@/api/rbac'
import { useAuthStore } from '@/stores/auth'
import type { SysDepart, SysDepartSaveRequest, LongId } from '@/types'

const list = ref<SysDepart[]>([])
const loading = ref(false)
// 下拉项不能用 v-auth（ElDropdownItem 根节点是 fragment）
const auth = useAuthStore()

/** 部门节点总数（含子级） */
const departCount = computed(() => {
  const count = (nodes: SysDepart[]): number =>
    nodes.reduce((sum, n) => sum + 1 + count(n.children ?? []), 0)
  return count(list.value)
})

async function load() {
  loading.value = true
  try {
    list.value = await rbacApi.departTree()
  } finally {
    loading.value = false
  }
}

// ── 新增 / 编辑 ──
const editVisible = ref(false)
const editFormRef = ref()
const saving = ref(false)
const editingId = ref<LongId | null>(null)
const form = ref<SysDepartSaveRequest>({
  name: '',
  parentId: null,
  sort: 0,
  status: 'ACTIVE',
  description: ''
})
const rules = {
  name: [{ required: true, message: '请输入部门名称', trigger: 'blur' }]
}

/** 上级部门下拉树：编辑时排除自身及后代（防环） */
const parentOptions = computed(() => {
  const exclude = new Set<LongId>()
  const collect = (nodes: SysDepart[], selfId: LongId | null) => {
    for (const n of nodes) {
      if (selfId !== null && n.id === selfId) {
        exclude.add(n.id)
        const addSub = (sub: SysDepart[]) => sub.forEach((s) => {
          exclude.add(s.id)
          if (s.children?.length) addSub(s.children)
        })
        if (n.children?.length) addSub(n.children)
        continue
      }
      collect(n.children ?? [], selfId)
    }
  }
  collect(list.value, editingId.value)
  const build = (nodes: SysDepart[]): Array<SysDepart & { disabled?: boolean }> =>
    nodes.map((n) => ({
      ...n,
      disabled: exclude.has(n.id),
      children: n.children?.length ? build(n.children) : undefined
    }))
  return build(list.value)
})

function openCreate(parent: SysDepart | null) {
  editingId.value = null
  form.value = {
    name: '',
    parentId: parent?.id ?? null,
    sort: 0,
    status: 'ACTIVE',
    description: ''
  }
  editVisible.value = true
}

function openEdit(row: SysDepart) {
  editingId.value = row.id
  form.value = {
    name: row.name,
    parentId: row.parentId ?? null,
    sort: row.sort ?? 0,
    status: row.status || 'ACTIVE',
    description: row.description ?? ''
  }
  editVisible.value = true
}

async function submitEdit() {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload: SysDepartSaveRequest = {
      ...form.value,
      description: form.value.description || null
    }
    if (editingId.value) {
      await rbacApi.updateDepart(editingId.value, payload)
    } else {
      await rbacApi.createDepart(payload)
    }
    ElMessage.success('保存成功')
    editVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function removeDepart(row: SysDepart) {
  try {
    await ElMessageBox.confirm(
      `确认删除部门「${row.name}」？存在子部门或已被用户关联时将被拒绝。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await rbacApi.deleteDepart(row.id)
  ElMessage.success('删除成功')
  load()
}

/** 「更多」下拉是否至少有一项：无权限时整个下拉不渲染 */
function hasMoreActions() {
  return auth.hasPermission('depart:add') || auth.hasPermission('depart:delete')
}

function handleMoreCommand(cmd: string, row: SysDepart) {
  if (cmd === 'child') openCreate(row)
  else if (cmd === 'remove') removeDepart(row)
}

onMounted(() => load())
</script>

<style scoped>
/* 页头 / 列表卡由 design-system.css 的 List Page Template 统一提供；
   本页无额外专属样式。 */
</style>
