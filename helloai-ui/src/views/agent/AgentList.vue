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
        <!-- 内部 LLM 注册：V49 模型选择（可用 Provider + 启用模型分组下拉）；留空走系统默认 provider+default-model -->
        <el-form-item v-if="form.accessType === 'API_KEY_LLM'" label="模型">
          <el-select
            v-model="form.modelType"
            placeholder="选择模型（留空使用平台默认）"
            clearable
            filterable
            :loading="modelsLoading"
            style="width:100%"
          >
            <el-option-group v-for="g in availableModels" :key="g.providerCode" :label="g.providerName">
              <el-option v-for="m in g.models" :key="m" :label="m" :value="g.providerCode + ':' + m" />
            </el-option-group>
          </el-select>
          <div class="field-hint">内部 LLM 使用平台密钥；留空则按系统默认 provider+default-model 绑定</div>
        </el-form-item>
        <el-form-item label="技能">
          <!-- V52 三段式：模型能力锁定 tag（不可取消，自动并入） -->
          <div v-if="form.accessType === 'API_KEY_LLM' && form.modelType && !skillDegraded" class="skill-cap-row">
            <el-tag
              v-for="s in skillCap"
              :key="s"
              size="small"
              type="primary"
              effect="plain"
              disable-transitions
            >{{ skillLabel(s) }}（模型能力）</el-tag>
          </div>
          <el-alert
            v-if="form.accessType === 'API_KEY_LLM' && form.modelType && skillDegraded"
            title="模型未上架，建议使用已上架模型；技能将按默认规则处理"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom:8px"
          />
          <el-select
            v-model="form.skills"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入技能（回车可自定义）"
            style="width:100%"
            :loading="skillOptionsLoading"
          >
            <el-option
              v-for="opt in skillSelectOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
              :disabled="opt.disabled"
              :title="opt.disabled ? '该模型不支持此技能' : ''"
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
import { ref, computed, onMounted, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { agentApi } from '@/api/agent'
import type { AvailableModelGroup } from '@/api/agent'
import { AGENT_SKILL_OPTIONS } from '@/constants/agentSkills'
import type { AgentListItem, AgentRole } from '@/types'
import AgentCard from './components/AgentCard.vue'
import AgentEditDialog from './components/AgentEditDialog.vue'
import AgentStatusDialog from './components/AgentStatusDialog.vue'
import AgentDeleteDialog from './components/AgentDeleteDialog.vue'
import AgentOnboardingDialog from './components/AgentOnboardingDialog.vue'

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
  // §6.74: 专业化已移除；skills 注册即填写（A2 显式技能优先）
  // V49: 内部 LLM（API_KEY_LLM）注册可显式选择模型，留空走系统默认 provider+default-model
  accessType: 'CLI_CLIENT',
  modelType: '',
  skills: [] as string[]
})
const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}

// ── 可用模型目录（内部 LLM 注册选模型用，V49 /agents/listAvailableModels）──
const availableModels = ref<AvailableModelGroup[]>([])
const modelsLoading = ref(false)

async function loadAvailableModels() {
  modelsLoading.value = true
  try {
    availableModels.value = await agentApi.listAvailableModels()
  } catch (e: any) {
    // 目录拉取失败不阻断注册（留空走默认），仅提示
    ElMessage.warning('模型目录加载失败：' + (e?.message || '未知错误'))
    availableModels.value = []
  } finally {
    modelsLoading.value = false
  }
}

watch(registerDialog, (open) => {
  if (open) {
    loadAvailableModels()
  }
})

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

// ── V52 技能区三段式：模型能力锁定 + 可选项白名单 + 降级提示 ──
const skillCap = ref<string[]>([])       // capabilitySkills（模型能力锁定，自动并入 form.skills）
const skillAvailable = ref<string[]>([]) // availableOptionalSkills（可扩展白名单）
const skillDegraded = ref(false)         // 模型未识别：降级为全量可编辑 + 提示
const skillOptionsLoading = ref(false)

function skillLabel(v: string) {
  return AGENT_SKILL_OPTIONS.find(o => o.value === v)?.label || v
}

async function loadSkillOptions(modelType: string) {
  skillOptionsLoading.value = true
  try {
    const res = await agentApi.skillOptions(modelType)
    skillCap.value = res.capabilitySkills || []
    skillAvailable.value = res.availableOptionalSkills || []
    skillDegraded.value = !!res.degraded
    // 能力锁定项强制并入（不可取消）
    for (const s of skillCap.value) {
      if (!form.skills.includes(s)) form.skills.push(s)
    }
  } catch {
    skillDegraded.value = true
  } finally {
    skillOptionsLoading.value = false
  }
}

watch(() => form.modelType, (mt) => {
  // 切模型/清空：先移除旧能力锁定项，避免残留不可用技能
  for (const s of skillCap.value) {
    const idx = form.skills.indexOf(s)
    if (idx >= 0) form.skills.splice(idx, 1)
  }
  skillCap.value = []
  skillAvailable.value = []
  skillDegraded.value = false
  if (form.accessType === 'API_KEY_LLM' && mt) {
    loadSkillOptions(mt)
  }
})

const skillSelectOptions = computed(() => {
  const isDriven = form.accessType === 'API_KEY_LLM' && !!form.modelType && !skillDegraded.value
  if (!isDriven) {
    // 外部 Agent / 模型留空 / 未识别降级：全量可编辑
    return AGENT_SKILL_OPTIONS.map(o => ({ ...o, disabled: false }))
  }
  // 能力驱动：锁定项由 tag 展示（下拉剔除），白名单可编辑，其余标准技能置灰（自定义仍可输入）
  return AGENT_SKILL_OPTIONS
    .filter(o => !skillCap.value.includes(o.value))
    .map(o => ({ ...o, disabled: !skillAvailable.value.includes(o.value) }))
})

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
      // V49: 内部 LLM 注册可选模型（providerCode:modelName）；留空由后端按系统默认 provider+default-model 补绑
      modelType: form.modelType || undefined,
      // 注册即填写技能（A2 显式优先）
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
    form.modelType = ''
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

/* V52 技能区：模型能力锁定 tag 行 */
.skill-cap-row {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
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
