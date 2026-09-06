<template>
  <div class="page ha-entrance-up">
    <!-- 页面标题 + 筛选/操作区（与 DutyLeaseList / ReviewList 共享模式） -->
    <div class="page-head">
      <h2 class="page-heading">
        Team 组合
      </h2>
      <div class="page-actions">
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          class="filter-select"
          @change="load(1)"
        >
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="ACTIVE" />
          <el-option label="已归档" value="ARCHIVED" />
        </el-select>
        <el-button
          size="small"
          type="primary"
          @click="openCreate"
        >
          新建 Team
        </el-button>
        <el-button
          size="small"
          @click="load(currentPage)"
        >
          刷新
        </el-button>
      </div>
    </div>

    <div class="card">
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
      >
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="成员数" width="90" align="center">
          <template #default="{ row }">
            {{ memberCountMap[row.id] ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openMembers(row)">
              成员
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              size="small"
              link
              type="primary"
              @click="onPublish(row)"
            >
              发布
            </el-button>
            <el-button
              v-if="row.status !== 'ARCHIVED'"
              size="small"
              link
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="row.status !== 'ARCHIVED'"
              size="small"
              link
              type="warning"
              @click="onArchive(row)"
            >
              归档
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          layout="total, prev, pager, next"
          :total="total"
          @current-change="load"
        />
      </div>
    </div>

    <!-- 新建/编辑弹窗 -->
    <el-dialog
      v-model="editVisible"
      :title="editingId ? '编辑 Team' : '新建 Team'"
      width="480px"
    >
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" placeholder="如：前端专项组" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 成员管理弹窗 -->
    <el-dialog
      v-model="membersVisible"
      :title="`成员管理：${currentTeam?.name ?? ''}`"
      width="560px"
    >
      <el-table :data="currentMembers" size="small">
        <el-table-column label="Agent" min-width="140">
          <template #default="{ row }">
            {{ agentName(row.agentId) }}
          </template>
        </el-table-column>
        <el-table-column label="槽位角色" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="roleTag(row.slotRole)">
              {{ row.slotRole }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="weight" label="权重" width="70" align="center" />
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button size="small" link type="danger" @click="onRemoveMember(row)">
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="member-add-row">
        <el-select
          v-model="addForm.agentId"
          placeholder="选择 Agent"
          filterable
          style="width: 220px"
        >
          <el-option
            v-for="a in addableAgents"
            :key="a.id"
            :label="`${a.name}（${a.role}）`"
            :value="a.id"
          />
        </el-select>
        <el-select
          v-model="addForm.slotRole"
          placeholder="槽位角色"
          style="width: 140px"
        >
          <el-option label="PLANNER" value="PLANNER" />
          <el-option label="EXECUTOR" value="EXECUTOR" />
          <el-option label="REVIEWER" value="REVIEWER" />
        </el-select>
        <el-button
          type="primary"
          :disabled="!addForm.agentId || !addForm.slotRole"
          :loading="saving"
          @click="onAddMember"
        >
          添加成员
        </el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { teamApi } from '@/api/team'
import { agentApi } from '@/api/agent'
import type { Agent, Team, TeamMember } from '@/types'

const loading = ref(false)
const rows = ref<Team[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')

const memberCountMap = reactive<Record<string, number>>({})

// 新建/编辑
const editVisible = ref(false)
const editingId = ref<number | string | null>(null)
const editForm = reactive({ name: '', description: '' })
const saving = ref(false)

// 成员管理
const membersVisible = ref(false)
const currentTeam = ref<Team | null>(null)
const currentMembers = ref<TeamMember[]>([])
const addForm = reactive<{ agentId: number | string | null; slotRole: string | null }>({
  agentId: null,
  slotRole: null
})
const agents = ref<Agent[]>([])

async function load(page = 1) {
  loading.value = true
  try {
    const data = await teamApi.page({
      page,
      size: pageSize.value,
      status: statusFilter.value || undefined
    })
    rows.value = data.list
    total.value = data.total
    currentPage.value = data.current || page
    // 并行取每个 Team 的成员数（轻量：只读成员列表计数）
    for (const t of rows.value) {
      const members = await teamApi.members(t.id)
      memberCountMap[t.id] = members.length
    }
  } finally {
    loading.value = false
  }
}

async function loadAgents() {
  try {
    agents.value = await agentApi.list({ status: 'ACTIVE' })
  } catch {
    agents.value = []
  }
}

function agentName(agentId: string | number): string {
  const hit = agents.value.find((a) => String(a.id) === String(agentId))
  return hit ? hit.name : `#${agentId}`
}

const addableAgents = computed<Agent[]>(() => {
  const currentIds = new Set(currentMembers.value.map((m) => String(m.agentId)))
  return agents.value.filter((a) => !currentIds.has(String(a.id)))
})

function openCreate() {
  editingId.value = null
  editForm.name = ''
  editForm.description = ''
  editVisible.value = true
}

function openEdit(row: Team) {
  editingId.value = row.id
  editForm.name = row.name
  editForm.description = row.description ?? ''
  editVisible.value = true
}

async function onSave() {
  if (!editForm.name.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await teamApi.update(editingId.value, editForm)
    } else {
      await teamApi.create(editForm)
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await load(currentPage.value)
  } finally {
    saving.value = false
  }
}

async function onPublish(row: Team) {
  try {
    await ElMessageBox.confirm(`发布后 Team「${row.name}」可作为任务 agent_policy.teamId 展开源，确认发布？`, '发布确认', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await teamApi.publish(row.id)
    ElMessage.success('已发布')
    await load(currentPage.value)
  } catch (e: any) {
    ElMessage.error(e?.message || '发布失败')
  }
}

async function onArchive(row: Team) {
  try {
    await ElMessageBox.confirm(`确认归档 Team「${row.name}」？归档后不可编辑与发布。`, '归档确认', {
      type: 'warning'
    })
  } catch {
    return
  }
  await teamApi.archive(row.id)
  ElMessage.success('已归档')
  await load(currentPage.value)
}

async function openMembers(row: Team) {
  currentTeam.value = row
  currentMembers.value = await teamApi.members(row.id)
  addForm.agentId = null
  addForm.slotRole = null
  membersVisible.value = true
}

async function onAddMember() {
  if (!addForm.agentId || !addForm.slotRole) return
  saving.value = true
  try {
    await teamApi.addMember(currentTeam.value!.id, {
      agentId: Number(addForm.agentId),
      slotRole: addForm.slotRole
    })
    ElMessage.success('成员已添加')
    currentMembers.value = await teamApi.members(currentTeam.value!.id)
    memberCountMap[currentTeam.value!.id] = currentMembers.value.length
    addForm.agentId = null
    addForm.slotRole = null
  } finally {
    saving.value = false
  }
}

async function onRemoveMember(member: TeamMember) {
  if (!currentTeam.value) return
  try {
    await ElMessageBox.confirm('确认移除该成员？', '移除确认', { type: 'warning' })
  } catch {
    return
  }
  await teamApi.removeMember(currentTeam.value.id, Number(member.agentId))
  ElMessage.success('成员已移除')
  currentMembers.value = await teamApi.members(currentTeam.value.id)
  memberCountMap[currentTeam.value.id] = currentMembers.value.length
}

function statusLabel(status: string) {
  const map: Record<string, string> = { DRAFT: '草稿', ACTIVE: '已发布', ARCHIVED: '已归档' }
  return map[status] ?? status
}

function statusTag(status: string) {
  const map: Record<string, 'info' | 'success' | 'warning'> = {
    DRAFT: 'info',
    ACTIVE: 'success',
    ARCHIVED: 'warning'
  }
  return map[status] ?? 'info'
}

function roleTag(role: string) {
  const map: Record<string, 'primary' | 'success' | 'warning'> = {
    PLANNER: 'primary',
    EXECUTOR: 'success',
    REVIEWER: 'warning'
  }
  return map[role] ?? 'primary'
}

onMounted(() => {
  load(1)
  loadAgents()
})
</script>

<style scoped>
.member-add-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  align-items: center;
}
</style>
