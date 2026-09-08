<template>
  <div class="page ha-entrance-up">
    <!-- 页面标题 + 筛选/操作区（与 DutyLeaseList / ReviewList 共享模式：标题外置，操作区右侧水平排列） -->
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
          @change="reload"
        >
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="ACTIVE" />
          <el-option label="已归档" value="ARCHIVED" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索 Team 名称或描述"
          clearable
          class="filter-search"
          :prefix-icon="Search"
          @input="onKeywordInput"
          @clear="reload"
        />
        <el-button
          size="small"
          @click="reload"
        >
          刷新
        </el-button>
        <el-button
          size="small"
          type="primary"
          @click="openCreate"
        >
          新建 Team
        </el-button>
      </div>
    </div>

    <!-- 顶部 4 个统计卡片：复用 design-system.css 全局 .stat-tile -->
    <div class="stats-grid ha-stagger-entrance">
      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            Team 总数
          </div>
          <div class="stat-tile-icon primary">
            <el-icon><UserFilled /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.total }}
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            运行中
          </div>
          <div class="stat-tile-icon success">
            <el-icon><VideoPlay /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.active }}
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            已发布
          </div>
          <div class="stat-tile-icon primary">
            <el-icon><Bell /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.published }}
        </div>
      </div>

      <div class="stat-tile ha-card-lift">
        <div class="stat-tile-head">
          <div class="stat-tile-label">
            已归档
          </div>
          <div class="stat-tile-icon warning">
            <el-icon><Box /></el-icon>
          </div>
        </div>
        <div class="stat-tile-value">
          {{ stats.archived }}
        </div>
      </div>
    </div>

    <!-- 列表卡片 -->
    <el-card
      v-loading="loading"
      class="ha-entrance-up list-card"
      style="animation-delay: 80ms"
    >
      <div class="table-wrapper">
        <el-table
          :data="filteredList"
          row-key="id"
          style="width: 100%"
          empty-text="暂无 Team"
        >
          <el-table-column label="名称" min-width="180">
            <template #default="{ row }">
              <div class="team-name-cell">
                <span class="team-name">{{ row.name }}</span>
                <span class="team-id">#{{ row.id }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="描述" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="desc-cell">{{ row.description || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small" effect="light">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成员数" width="90" align="center">
            <template #default="{ row }">
              {{ memberCountMap[row.id] ?? 0 }} 人
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">
              {{ fmtTime(row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" :width="ACTION.FOUR" fixed="right">
            <template #default="{ row }">
              <div class="action-cell">
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
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-pagination
        v-if="filteredList.length > 0"
        background
        layout="prev, pager, next, total"
        :total="filteredList.length"
        :page-size="pageSize"
        :current-page="currentPage"
        class="list-pagination"
        @current-change="onPageChange"
      />
    </el-card>

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
            <el-tag size="small" :type="roleTag(row.slotRole)" effect="light">
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
import {
  Search,
  UserFilled,
  VideoPlay,
  Bell,
  Box
} from '@element-plus/icons-vue'
import { teamApi } from '@/api/team'
import { agentApi } from '@/api/agent'
import type { Agent, Team, TeamMember } from '@/types'
import { fmtTime, ACTION } from '@/utils/tableConfig'

const loading = ref(false)
const rows = ref<Team[]>([])
const keyword = ref('')
let searchTimer: ReturnType<typeof setTimeout> | null = null

const memberCountMap = reactive<Record<string, number>>({})

// 客户端分页
const pageSize = ref(10)
const currentPage = ref(1)
const statusFilter = ref('')

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

// 顶部统计：基于全量 rows 实时聚合
// 运行中 = DRAFT（待发布/编辑中的 Team）
// 已发布 = ACTIVE（任务可引用的 Team）
// 已归档 = ARCHIVED
const stats = computed(() => {
  const source = rows.value
  let active = 0
  let published = 0
  let archived = 0
  for (const t of source) {
    if (t.status === 'DRAFT') active++
    else if (t.status === 'ACTIVE') published++
    else if (t.status === 'ARCHIVED') archived++
  }
  return { total: source.length, active, published, archived }
})

// 列表过滤：状态筛选 + 关键词搜索（Team 名 / 描述）
const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => {
    if (statusFilter.value && row.status !== statusFilter.value) return false
    if (!kw) return true
    return (row.name || '').toLowerCase().includes(kw)
      || (row.description || '').toLowerCase().includes(kw)
  })
})

async function load() {
  loading.value = true
  try {
    const data = await teamApi.page({ page: 1, size: 1000 })
    rows.value = data.list
    // 并行取每个 Team 的成员数（轻量：只读成员列表计数）
    for (const t of rows.value) {
      const members = await teamApi.members(t.id)
      memberCountMap[t.id] = members.length
    }
  } finally {
    loading.value = false
  }
}

function reload() {
  currentPage.value = 1
  load()
}

function onKeywordInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    currentPage.value = 1
  }, 200)
}

function onPageChange(p: number) {
  currentPage.value = p
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
    await load()
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
    await load()
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
  await load()
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
  load()
  loadAgents()
})
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
.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-select { width: 140px; }
.filter-search { width: 260px; }
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
  flex-shrink: 0;
}

/* 列表卡片：flex:1 吸收统计卡以下的剩余高度，让数据少时卡片仍撑到底；
    display:flex 让内部 el-table 也能按卡片高度排版 */
.list-card {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
/* el-card 默认是 block，让 .list-card 内部元素接管卡片高度 */
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
/* 让内层 el-table 在 flex 父级里真正撑开，承载空数据 / 短数据的空白填充 */
.table-wrapper :deep(.el-table) {
  flex: 1 1 auto;
  min-height: 0;
}
/* 分页：贴底部、居中；margin-top:16px 由下方样式接管 */
.list-pagination {
  margin-top: 16px;
  justify-content: center;
  flex-shrink: 0;
}

/* 名称列：主名 + ID 副信息，与 DutyLeaseList 的 agent-cell 同构 */
.team-name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.team-name {
  font-weight: 600;
  color: var(--ha-ink);
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}
.team-id {
  font-family: var(--ha-font-mono);
  font-size: 12px;
  color: var(--ha-primary);
}

/* 描述列：限高 + ellipsis */
.desc-cell {
  color: var(--ha-ink-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.member-add-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  align-items: center;
}

@media (max-width: 1200px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 640px) {
  .stats-grid { grid-template-columns: 1fr; }
  .filter-select, .filter-search { width: 100%; }
}
</style>