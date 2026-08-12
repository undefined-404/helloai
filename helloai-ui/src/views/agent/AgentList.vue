<template>
  <div class="page ha-entrance-up">
    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="keyword"
          placeholder="搜索 Agent 名称或描述..."
          prefix-icon="Search"
          clearable
          class="search-input"
          @keyup.enter="search"
          @clear="search"
        />
        <div class="role-filters">
          <button
            v-for="r in roleFilters"
            :key="r.value"
            :class="['role-pill', { active: filterRole === r.value }]"
            @click="setFilter(r.value)"
          >
            {{ r.label }}
          </button>
        </div>
      </div>
      <div class="toolbar-right">
        <el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
        <el-button type="primary" @click="registerDialog = true">注册 Agent</el-button>
      </div>
    </div>

    <!-- 卡片网格 -->
    <div v-loading="loading" class="card-grid" v-if="!loading || list.length > 0">
      <AgentCard
        v-for="(agent, idx) in list"
        :key="agent.id"
        :agent="agent"
        :index="idx"
        @click="goDetail"
        @edit="openEdit"
        @toggle-status="openToggleStatus"
        @delete="openDelete"
        @onboarding="openOnboarding"
      />

      <!-- 空状态 -->
      <el-empty
        v-if="!loading && list.length === 0"
        description="暂无 Agent"
        :image-size="80"
        style="grid-column: 1 / -1"
      />
    </div>

    <!-- 骨架加载 -->
    <div v-if="loading && list.length === 0" class="card-grid">
      <div v-for="i in 6" :key="i" class="ha-skeleton" style="height:180px;border-radius:var(--ha-radius-lg)" />
    </div>

    <!-- 分页 -->
    <div v-if="total > 0" class="pagination-bar">
      <span class="page-info">第 {{ current }} / {{ pages }} 页，共 {{ total }} 条</span>
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="current"
        @current-change="loadPage"
      />
    </div>

    <!-- 注册弹窗 -->
    <el-dialog v-model="registerDialog" title="注册新 Agent" width="480px" top="5vh" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width:100%" @change="onRoleChange">
            <el-option label="规划器 PLANNER" value="PLANNER" />
            <el-option label="执行器 EXECUTOR" value="EXECUTOR" />
            <el-option label="审查器 REVIEWER" value="REVIEWER" />
          </el-select>
        </el-form-item>
        <el-form-item label="接入类型" prop="accessType">
          <el-select v-model="form.accessType" style="width:100%" @change="onAccessTypeChange">
            <el-option label="外部 AI Agent（CLI 接入）" value="CLI_CLIENT" />
            <el-option label="内部 LLM（API Key）" value="API_KEY_LLM" />
            <!-- 网页端 Planner 仅 PLANNER 角色可选，当前功能未开放 -->
            <el-option v-if="form.role === 'PLANNER'" label="网页端 Planner" value="WEB_BROWSER" />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="form.accessType === 'WEB_BROWSER'"
          title="网页端 Planner 功能暂不可用，敬请期待"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom:16px"
        />
        <!-- §6.74: 模型选择已移除——内部 LLM 统一走系统配置默认 provider+default-model，后端自动补绑平台密钥 -->
        <el-form-item label="技能">
          <el-select
            v-model="form.skills"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入技能（回车可自定义）"
            style="width:100%"
          >
            <el-option
              v-for="opt in skillOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="field-hint">能力声明，任务「要求技能」按 AND 语义匹配；不填则按名称/描述自动推导</div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="registering"
          :disabled="form.accessType === 'WEB_BROWSER'"
          @click="handleRegister"
        >注册</el-button>
      </template>
    </el-dialog>

    <!-- 编辑弹窗 -->
    <AgentEditDialog v-model="editDialog" :agent="editTarget" @saved="load()" />

    <!-- 状态切换弹窗 -->
    <AgentStatusDialog v-model="statusDialog" :agent="statusTarget" @done="load()" />

    <!-- 删除确认弹窗 -->
    <AgentDeleteDialog v-model="deleteDialog" :agent="deleteTarget" @done="load()" />

    <!-- 接入内容生成弹窗 -->
    <AgentOnboardingDialog v-model="onboardingDialog" :agent-id="onboardingAgentId" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { agentApi } from '@/api/agent'
import { AGENT_SKILL_OPTIONS } from '@/constants/agentSkills'
import type { AgentListItem, AgentRole } from '@/types'
import AgentCard from './components/AgentCard.vue'
import AgentEditDialog from './components/AgentEditDialog.vue'
import AgentStatusDialog from './components/AgentStatusDialog.vue'
import AgentDeleteDialog from './components/AgentDeleteDialog.vue'
import AgentOnboardingDialog from './components/AgentOnboardingDialog.vue'

const skillOptions = AGENT_SKILL_OPTIONS

const router = useRouter()

// ── 筛选 ──
const keyword = ref('')
const filterRole = ref<string>('all')
const roleFilters = [
  { value: 'all', label: '全部' },
  { value: 'PLANNER', label: '规划者' },
  { value: 'EXECUTOR', label: '执行者' },
  { value: 'REVIEWER', label: '审查者' },
]

function setFilter(v: string) {
  filterRole.value = v
  load()
}

function search() {
  load()
}

// ── 分页 ──
const list = ref<AgentListItem[]>([])
const total = ref(0)
const current = ref(1)
const pages = ref(1)
const pageSize = 12
const loading = ref(false)

async function load(page = 1) {
  loading.value = true
  try {
    const params: any = { page, pageSize }
    if (filterRole.value !== 'all') params.role = filterRole.value
    if (keyword.value) params.keyword = keyword.value
    const res = await agentApi.adminList(params)
    list.value = res.list || []
    total.value = res.total
    current.value = page
    pages.value = res.pages || 1
  } finally {
    loading.value = false
  }
}

function loadPage(p: number) { load(p) }

function goDetail(id: string) {
  router.push(`/agents/${id}`)
}

// ── 注册 ──
const registerDialog = ref(false)
const registering = ref(false)
const formRef = ref()
const form = reactive({
  name: '',
  role: 'EXECUTOR',
  description: '',
  // §6.74: 专业化/模型选择已移除；skills 注册即填写（A2 显式技能优先）
  accessType: 'CLI_CLIENT',
  skills: [] as string[]
})
const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}

// ── LLM Provider 目录（内部 LLM 注册用，按后端已生效 api-key 配置枚举）──
// §6.74: 已移除模型选择——内部 LLM 统一走系统配置默认 provider+default-model

function onRoleChange() {
  // 网页端 Planner 仅 PLANNER 角色可选，切走角色后回退默认接入类型
  if (form.role !== 'PLANNER' && form.accessType === 'WEB_BROWSER') {
    form.accessType = 'CLI_CLIENT'
  }
}

function onAccessTypeChange(v: string) {
  if (v === 'WEB_BROWSER') {
    ElMessage.warning('网页端 Planner 功能暂不可用')
  }
}

async function handleRegister() {
  if (form.accessType === 'WEB_BROWSER') {
    ElMessage.warning('网页端 Planner 功能暂不可用')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  registering.value = true
  try {
    const isLlm = form.accessType === 'API_KEY_LLM'
    const res: any = await agentApi.register({
      name: form.name,
      role: form.role,
      description: form.description,
      accessType: form.accessType,
      // 注册即填写技能（A2 显式优先）；§6.74: 不再传 modelType，内部 LLM 由后端按系统默认 provider+default-model 补绑
      skills: form.skills.length > 0 ? form.skills : undefined
    })
    registerDialog.value = false
    if (isLlm) {
      // 内部 LLM Agent 无需 CLI 接入内容，注册即完成（平台密钥已自动绑定）
      ElMessage.success('内部 LLM Agent 注册成功，平台密钥已自动绑定')
    } else {
      // 外部 Agent：注册成功后直接打开 onboarding 弹窗
      onboardingAgentId.value = res.id
      onboardingDialog.value = true
    }
    // 重置表单
    form.name = ''
    form.description = ''
    form.accessType = 'CLI_CLIENT'
    form.skills = []
    load()
  } finally { registering.value = false }
}

// ── 编辑/状态/删除操作 ──
const editDialog = ref(false)
const editTarget = ref<AgentListItem | null>(null)
function openEdit(agent: AgentListItem) { editTarget.value = agent; editDialog.value = true }

const statusDialog = ref(false)
const statusTarget = ref<AgentListItem | null>(null)
function openToggleStatus(agent: AgentListItem) { statusTarget.value = agent; statusDialog.value = true }

const deleteDialog = ref(false)
const deleteTarget = ref<AgentListItem | null>(null)
function openDelete(agent: AgentListItem) { deleteTarget.value = agent; deleteDialog.value = true }

// ── 接入内容生成 ──
const onboardingDialog = ref(false)
const onboardingAgentId = ref<string | number | null>(null)
function openOnboarding(agent: AgentListItem) {
  onboardingAgentId.value = agent.id
  onboardingDialog.value = true
}

onMounted(() => load())
</script>

<style scoped>
/* ── 工具栏 ── */
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.toolbar-left {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.search-input {
  max-width: 360px;
}

.role-filters {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.role-pill {
  padding: 4px 14px;
  font-size: 13px;
  font-family: var(--ha-font-family);
  border: 1px solid var(--ha-border);
  border-radius: 999px;
  background: transparent;
  color: var(--ha-ink-secondary);
  cursor: pointer;
  transition: all var(--ha-duration-fast) var(--ha-ease-out);
}

.role-pill:hover {
  border-color: var(--ha-primary);
  color: var(--ha-primary);
}

.role-pill.active {
  background: var(--ha-primary);
  color: #fff;
  border-color: var(--ha-primary);
}

/* ── 卡片网格 ── */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

/* ── 分页 ── */
.pagination-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 24px;
  flex-wrap: wrap;
  gap: 12px;
}

.page-info {
  font-size: 13px;
  color: var(--ha-muted);
}

@media (max-width: 768px) {
  .card-grid {
    grid-template-columns: 1fr;
  }
  .toolbar {
    flex-direction: column;
  }
  .search-input {
    max-width: 100%;
  }
  .pagination-bar {
    justify-content: center;
  }
}
</style>
