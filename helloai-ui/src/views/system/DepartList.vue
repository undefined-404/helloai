<template>
  <div class="page ha-entrance-up">
    <div class="page-head">
      <h2 class="page-heading">
        部门管理
      </h2>
      <div class="page-actions">
        <el-button
          v-auth="'depart:add'"
          size="small"
          type="primary"
          @click="openCreate(null)"
        >
          <el-icon><Plus /></el-icon>
          <span>新增部门</span>
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
          :width="ACTION.FOUR"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-auth="'depart:add'"
              size="small"
              link
              type="primary"
              @click="openCreate(row)"
            >
              新增子
            </el-button>
            <el-button
              v-auth="'depart:edit'"
              size="small"
              link
              type="primary"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-auth="'depart:delete'"
              size="small"
              link
              type="danger"
              @click="removeDepart(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑 部门 -->
    <el-dialog
      v-model="editVisible"
      :title="editingId ? '编辑部门' : '新增部门'"
      width="480px"
      class="ha-dialog--form"
      :style="{ height: 'calc(100vh - 12vh - 32px)', minHeight: '420px', display: 'flex', flexDirection: 'column' }"
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
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rbacApi } from '@/api/rbac'
import type { SysDepart, SysDepartSaveRequest, LongId } from '@/types'
import { ACTION } from '@/utils/tableConfig'

const list = ref<SysDepart[]>([])
const loading = ref(false)

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
</style>
