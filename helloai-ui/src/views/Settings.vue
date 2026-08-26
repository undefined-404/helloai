<template>
  <div class="page settings-page ha-entrance-up">
    <!-- ============================================================
         顶部 Hero 区：品牌锚点 + 标题/副标题
         ============================================================ -->
    <header class="page-hero">
      <div class="page-hero-icon" aria-hidden="true">
        <el-icon :size="20">
          <Setting />
        </el-icon>
      </div>
      <p class="page-hero-subtitle">
        配置系统基础参数、模型供应商与通知方式
      </p>
    </header>

    <!-- ============================================================
         主包裹卡 sheet：所有设置内容收纳在一张大卡内，铺满页面宽度
         ============================================================ -->
    <el-card class="settings-sheet" shadow="never">
      <header class="sheet-header">
        <div class="sheet-header-text">
          <h1 class="sheet-title">系统设置</h1>
          <p class="sheet-subtitle">以下配置修改后点击底部「保存设置」按钮生效</p>
        </div>
        <el-button
          size="small"
          plain
          :disabled="!isDirty"
          @click="handleReset"
        >
          <el-icon><RefreshLeft /></el-icon>
          <span>重置</span>
        </el-button>
      </header>

      <el-form
        ref="formRef"
        :model="form"
        label-width="120px"
        class="settings-form"
      >
        <div class="settings-stack">
        <!-- 1 基础配置 -->
        <div
          class="section-block"
        >
          <div class="section-header">
            <div class="section-mark">
              <span class="section-mark-num">1</span>
              <h2 class="section-mark-title">
                基础配置
              </h2>
            </div>
            <el-button
              size="small"
              plain
              :disabled="!isDirty"
              @click="handleReset"
            >
              <el-icon><RefreshLeft /></el-icon>
              <span>重置</span>
            </el-button>
          </div>
          <el-form-item label="外部访问地址">
            <el-input
              v-model="form.externalUrl"
              placeholder="http://192.168.1.100:6565"
            />
            <div class="form-hint">
              用于生成 SKILL 接入内容（Agent 凭此地址回连本平台）。
              本机可直接用 <code>http://localhost:6565</code>，其他设备用
              <code>http://&lt;本机IP&gt;:6565</code>，公网部署用域名或公网 IP。
            </div>
          </el-form-item>
          <el-form-item label="质量门控">
            <div class="switch-field">
              <el-switch
                v-model="form.qualityGateEnabled"
                :before-change="beforeQualityGateChange"
              />
              <!-- 文字态双保险：开关动画视觉之外提供不依赖颜色的状态通道 -->
              <span
                class="switch-state"
                :class="form.qualityGateEnabled ? 'on' : 'off'"
              >{{ form.qualityGateEnabled ? '已开启' : '已关闭' }}</span>
            </div>
            <div class="form-hint">
              默认开启；关闭后
              <router-link
                class="hint-link"
                to="/quality-dashboard"
              >质量看板</router-link>
              及画像重算、自动派发等管理侧入口不可用（§6.151 起默认开放）。
            </div>
          </el-form-item>
        </div>

        <!-- 2 联网搜索 -->
        <div
          class="section-block"
        >
          <div class="section-header">
            <div class="section-mark">
              <span class="section-mark-num">2</span>
              <h2 class="section-mark-title">
                联网搜索
              </h2>
            </div>
            <el-button
              size="small"
              plain
              :loading="verifyingWebSearchKey"
              @click="verifyWebSearchKey(true)"
            >
              <el-icon><CircleCheck /></el-icon>
              <span>验证 Key</span>
            </el-button>
          </div>
          <el-form-item label="博查 API Key">
            <el-input
              v-model="form.webSearchApiKey"
              type="password"
              show-password
              placeholder="输入新 Key 并保存；清空并保存则移除 Key"
            />
            <div class="form-hint">
              需求对话每轮联网检索（默认供应商博查）使用，加密存储，保存后立即生效无需重启。
              <el-tag
                v-if="webSearchKeyConfigured"
                type="success"
                size="small"
                class="hint-tag"
              >已配置</el-tag>
              <el-tag
                v-else
                type="warning"
                size="small"
                class="hint-tag"
              >未配置 · 联网搜索不可用</el-tag>
            </div>
            <div
              v-if="webSearchDirty"
              class="verify-key-tip"
            >验证的是已保存的 Key；输入框改动请先保存</div>
          </el-form-item>
        </div>

        <!-- 3 LLM 供应商 -->
        <div
          class="section-block"
        >
          <div class="section-header">
            <div class="section-mark">
              <span class="section-mark-num">3</span>
              <h2 class="section-mark-title">
                LLM 供应商
              </h2>
            </div>
            <el-button
              type="primary"
              size="small"
              :icon="Plus"
              @click="openPickerDialog"
            >
              添加模型
            </el-button>
          </div>

          <div class="provider-layout">
            <!-- 左侧列表：role="listbox" + option 支持键盘漫游（Tab 进入，↑↓ 移动，Enter/空格 选中） -->
            <div
              class="provider-list"
              role="listbox"
              aria-label="LLM 供应商列表"
            >
              <div
                v-for="(p, idx) in providers"
                :key="p.id"
                class="provider-item"
                :class="{ active: selectedId === p.id }"
                role="option"
                :aria-selected="selectedId === p.id"
                tabindex="0"
                @click="selectedId = p.id"
                @keydown="onProviderKeydown($event, idx)"
              >
                <div class="provider-item-name">
                  <el-icon
                    v-if="p.enabled === 1"
                    class="status-dot on"
                  >
                    <CircleCheckFilled />
                  </el-icon>
                  <el-icon
                    v-else
                    class="status-dot off"
                  >
                    <CircleCloseFilled />
                  </el-icon>
                  <span>{{ p.providerName }}</span>
                  <el-tag
                    v-if="p.builtin === 1"
                    type="info"
                    size="small"
                    class="tag-builtin"
                  >
                    内置
                  </el-tag>
                </div>
                <div class="provider-item-code">
                  {{ p.providerCode }}
                </div>
              </div>
              <el-empty
                v-if="!providersLoading && providers.length === 0"
                description="还没有配置任何 LLM Provider"
                :image-size="64"
              />
            </div>

            <!-- 右侧详情 -->
            <div
              v-if="selectedProvider"
              class="provider-detail"
            >
              <div class="detail-header">
                <h3 class="detail-title">
                  <span class="detail-logo">
                    <el-icon :size="16"><MagicStick /></el-icon>
                  </span>
                  {{ selectedProvider.providerName }}
                  <el-tag
                    v-if="selectedProvider.builtin === 1"
                    type="info"
                    size="small"
                  >
                    内置
                  </el-tag>
                </h3>
                <div class="detail-actions">
                  <el-button
                    v-if="selectedProvider.builtin !== 1"
                    size="small"
                    plain
                    @click="openEditDialog(selectedProvider)"
                  >
                    编辑
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    @click="openKeyDialog(selectedProvider)"
                  >
                    配置 Key
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    :loading="verifyingKeyId === selectedProvider.id"
                    @click="verifyProviderKey(selectedProvider.id)"
                  >
                    验证 Key
                  </el-button>
                  <el-button
                    v-if="selectedProvider.builtin !== 1"
                    size="small"
                    plain
                    @click="handleToggle(selectedProvider)"
                  >
                    {{ selectedProvider.enabled === 1 ? '禁用' : '启用' }}
                  </el-button>
                  <el-button
                    v-if="selectedProvider.builtin !== 1"
                    size="small"
                    type="danger"
                    plain
                    @click="handleDelete(selectedProvider)"
                  >
                    删除
                  </el-button>
                </div>
              </div>

              <el-descriptions
                :column="1"
                border
                size="small"
                class="detail-desc"
              >
                <el-descriptions-item label="协议">
                  {{ protocolLabel(selectedProvider.protocolType) }}
                </el-descriptions-item>
                <el-descriptions-item label="计费类型">
                  {{ billingLabel(selectedProvider.billingType) }}
                </el-descriptions-item>
                <el-descriptions-item label="Provider Code">
                  <code>{{ selectedProvider.providerCode }}</code>
                </el-descriptions-item>
                <el-descriptions-item label="Base URL">
                  <span class="base-url-cell">
                    <span class="base-url-text">{{ selectedProvider.baseUrl || '-' }}</span>
                    <el-button
                      v-if="selectedProvider.baseUrl"
                      size="small"
                      plain
                      class="base-url-copy"
                      @click="copyText(selectedProvider.baseUrl)"
                    >
                      复制
                    </el-button>
                  </span>
                </el-descriptions-item>
                <el-descriptions-item label="默认模型">
                  {{ selectedProviderDefaultModel || selectedProvider.defaultModel || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="API Key">
                  <span v-if="selectedProvider.apiKeyConfigured">{{ selectedProvider.apiKeyMasked }}</span>
                  <span
                    v-else
                    class="key-missing"
                  >未配置</span>
                  <el-tag
                    v-if="selectedProvider.apiKeyFromVault"
                    type="success"
                    size="small"
                    class="key-source"
                  >
                    vault
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="状态">
                  <el-tag
                    v-if="selectedProvider.enabled === 1"
                    type="success"
                    size="small"
                    effect="light"
                  >
                    已启用
                  </el-tag>
                  <el-tag
                    v-else
                    type="info"
                    size="small"
                    effect="light"
                  >
                    已禁用
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>

              <div class="model-section">
                <div class="model-section-header">
                  <span class="model-section-title">
                    <el-icon class="model-section-icon"><Cpu /></el-icon>
                    模型配置
                  </span>
                  <div class="model-section-actions">
                    <template v-if="!isBuiltinSelected">
                      <el-button
                        size="small"
                        :disabled="!providerModels.length"
                        @click="selectAllModels"
                      >
                        全选
                      </el-button>
                      <el-button
                        size="small"
                        :disabled="!providerModels.length"
                        @click="checkedModels = []"
                      >
                        清空
                      </el-button>
                    </template>
                    <el-button
                      v-if="!isBuiltinSelected"
                      size="small"
                      type="primary"
                      :loading="modelSaving"
                      @click="handleSaveModels"
                    >
                      保存模型配置
                    </el-button>
                  </div>
                </div>

                <el-checkbox-group
                  v-model="checkedModels"
                  class="model-checkbox-group"
                >
                  <el-checkbox
                    v-for="m in providerModels"
                    :key="m.modelName"
                    :label="m.modelName"
                    :disabled="isBuiltinSelected"
                    class="model-checkbox"
                  >
                    <span class="model-row">
                      <span class="model-row-name">{{ m.modelName }}</span>
                      <el-tag
                        v-if="m.isDefault === 1"
                        type="warning"
                        size="small"
                        class="model-row-tag"
                      >
                        默认
                      </el-tag>
                      <el-tag
                        v-if="m.enabled !== 1"
                        type="info"
                        size="small"
                        class="model-row-tag"
                      >
                        已禁用
                      </el-tag>
                    </span>
                  </el-checkbox>
                </el-checkbox-group>
                <el-empty
                  v-if="!providerModels.length"
                  description="该 Provider 还没有配置模型"
                  :image-size="48"
                />

                <div
                  v-if="!isBuiltinSelected"
                  class="custom-model-row"
                >
                  <el-input
                    v-model="customModelInput"
                    placeholder="输入自定义模型名称，回车添加"
                    class="custom-model-input"
                    @keyup.enter="addCustomModel"
                  />
                  <el-button
                    size="small"
                    @click="addCustomModel"
                  >
                    添加
                  </el-button>
                </div>

                <div class="default-model-row">
                  <span class="default-model-label">默认模型</span>
                  <el-select
                    v-if="!isBuiltinSelected"
                    v-model="selectedDefaultModel"
                    placeholder="从已选模型中选择"
                    style="width: 240px"
                  >
                    <el-option
                      v-for="m in checkedModels"
                      :key="m"
                      :label="m"
                      :value="m"
                    />
                  </el-select>
                  <el-tag
                    v-else
                    type="warning"
                    effect="light"
                  >
                    {{ selectedProviderDefaultModel || '-' }}
                  </el-tag>
                </div>

                <div class="form-hint">
                  每个 Provider 必须至少配置一个启用模型并指定默认模型；内置供应商的预设模型固定不可修改。
                </div>
              </div>

              <div class="detail-hint">
                <el-alert
                  type="warning"
                  :closable="false"
                  show-icon
                >
                  <template #title>
                    启用后才能在 Agent 注册时被选为默认 provider；禁用仅是管理侧的"软隐藏"，不会删除任何 Agent。
                  </template>
                </el-alert>
              </div>
            </div>
            <div
              v-else
              class="provider-detail placeholder"
            >
              <el-empty
                description="左侧选择一个 Provider 查看详情"
                :image-size="64"
              />
            </div>
          </div>
        </div>

        <!-- 4 通知配置 -->
        <div
          class="section-block"
        >
          <div class="section-header">
            <div class="section-mark">
              <span class="section-mark-num">4</span>
              <h2 class="section-mark-title">
                通知配置
              </h2>
            </div>
          </div>
          <el-form-item label="通知方式">
            <div class="switch-field">
              <el-checkbox
                v-model="form.notifyChannels"
                label="web"
                disabled
                class="notify-checkbox"
              >
                <span class="notify-label">
                  <el-icon><BellFilled /></el-icon>
                  站内通知
                </span>
              </el-checkbox>
            </div>
            <div class="form-hint">
              系统消息推送。当前仅支持站内通知，其他通道（邮件、IM）后续接入。
            </div>
          </el-form-item>
        </div>
      </div>
    </el-form>

    <!-- 主卡底部保存区：嵌入 settings-sheet，不悬浮 -->
    <footer class="sheet-footer">
      <div class="sheet-footer-actions">
        <el-button
          :disabled="!isDirty"
          @click="handleReset"
        >
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="handleSave"
        >
          保存设置
        </el-button>
      </div>
    </footer>
  </el-card>

    <!-- 配置 API Key 对话框 -->
    <el-dialog
      v-model="keyDialogVisible"
      :title="'配置 API Key：' + (keyDialogProvider?.providerName || '')"
      width="480px"
    >
      <el-input
        v-model="newApiKey"
        type="password"
        show-password
        placeholder="输入新 API Key（将覆盖旧 Key，实时生效无需重启）"
      />
      <template #footer>
        <el-button @click="keyDialogVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          @click="handleSaveKey"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 添加 / 编辑 Provider 对话框 -->
    <el-dialog
      v-model="formDialogVisible"
      :title="formDialogMode === 'create' ? '添加 LLM 供应商' : '编辑 LLM 供应商'"
      width="560px"
    >
      <el-form
        ref="formDialogRef"
        :model="formDraft"
        :rules="formRules"
        label-width="110px"
      >
        <el-form-item
          label="Provider Code"
          prop="providerCode"
        >
          <el-input
            v-model="formDraft.providerCode"
            placeholder="小写字母数字中划线，如 my-openai"
            :disabled="formDialogMode === 'edit'"
          />
          <div class="form-hint">
            唯一标识，创建后不可修改（内置 Provider 不可改）
          </div>
        </el-form-item>
        <el-form-item
          label="显示名称"
          prop="providerName"
        >
          <el-input
            v-model="formDraft.providerName"
            placeholder="如 我的 OpenAI"
          />
        </el-form-item>
        <el-form-item
          label="协议类型"
          prop="protocolType"
        >
          <el-select
            v-model="formDraft.protocolType"
            style="width: 100%"
          >
            <el-option
              v-for="opt in PROTOCOL_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          label="Base URL"
          prop="baseUrl"
        >
          <el-input
            v-model="formDraft.baseUrl"
            placeholder="如 https://api.openai.com/v1"
          />
        </el-form-item>
        <el-form-item
          v-if="formDialogMode === 'create'"
          label="API Key"
        >
          <el-input
            v-model="formDraft.apiKey"
            type="password"
            show-password
            placeholder="新增时可一并填写；也可稍后单独配置"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialogVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          @click="handleSubmitForm"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 添加模型两步式弹窗（V59）：第一步选供应商，第二步填类型 + API 密钥并自动验证 -->
    <ProviderPickerDialog
      v-model="pickerVisible"
      @pick="handlePickProvider"
    />
    <AddModelFormDialog
      v-model="addModelVisible"
      :providers="providers"
      :initial="addModelInitial"
      @added="handleModelAdded"
    />
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, computed, watch } from 'vue'
import { ElMessage, ElMessageBox, FormInstance, FormRules } from 'element-plus'
import {
  Plus,
  CircleCheckFilled,
  CircleCloseFilled,
  WarningFilled,
  Setting,
  RefreshLeft,
  CircleCheck,
  MagicStick,
  Cpu,
  BellFilled
} from '@element-plus/icons-vue'
import {
  settingsApi,
  LlmProviderResponse,
  LlmProviderModelResponse,
  CreateLlmProviderRequest,
  ProtocolType,
  PROTOCOL_OPTIONS,
  BILLING_TYPE_OPTIONS
} from '@/api/settings'
import ProviderPickerDialog from './settings/ProviderPickerDialog.vue'
import AddModelFormDialog from './settings/AddModelFormDialog.vue'
import type { CatalogProvider } from './settings/providerCatalog'

const formRef = ref()
const form = reactive({
  externalUrl: '',
  qualityGateEnabled: false,
  webSearchApiKey: '',
  notifyChannels: ['web'] as string[]
})

// 博查 Key 加载时的脱敏回显值（'********'）；未改动则保存时跳过提交，避免把掩码当 Key 写入。
const webSearchKeyLoaded = ref('')
const webSearchKeyConfigured = ref(false)

/** 博查 Key 未保存变更指示：保存的是已落库的 Key，验证前提示先保存。 */
const webSearchDirty = computed(() => form.webSearchApiKey !== webSearchKeyLoaded.value)

/** 未保存变更指示：加载快照与当前表单值比对（保存成功后同步快照）。 */
const loadedExternalUrl = ref('')
const loadedQualityGate = ref(false)
const saving = ref(false)

const isDirty = computed(() =>
  form.externalUrl !== loadedExternalUrl.value
  || form.qualityGateEnabled !== loadedQualityGate.value
  || form.webSearchApiKey !== webSearchKeyLoaded.value
)

const providers = ref<LlmProviderResponse[]>([])
const providersLoading = ref(false)
const selectedId = ref<number | null>(null)

const selectedProvider = computed<LlmProviderResponse | null>(
  () => providers.value.find(p => p.id === selectedId.value) || null
)

// ---- 模型配置（V49）----
const providerModels = ref<LlmProviderModelResponse[]>([])
const checkedModels = ref<string[]>([])
const selectedDefaultModel = ref('')
const customModelInput = ref('')
const modelSaving = ref(false)

/** 内置 Provider 的预设模型固定不可修改。 */
const isBuiltinSelected = computed(() => selectedProvider.value?.builtin === 1)

/** 模型配置区的默认模型（以 llm_provider_model 表为准）。 */
const selectedProviderDefaultModel = computed(
  () => providerModels.value.find(m => m.isDefault === 1)?.modelName || ''
)

watch(selectedProvider, () => {
  loadModels()
})

const keyDialogVisible = ref(false)
const keyDialogProvider = ref<LlmProviderResponse | null>(null)
const newApiKey = ref('')

const formDialogVisible = ref(false)
const formDialogMode = ref<'create' | 'edit'>('create')
const formDialogRef = ref<FormInstance>()
const formDraft = reactive<CreateLlmProviderRequest & { apiKey?: string }>({
  providerCode: '',
  providerName: '',
  protocolType: 'OPENAI_COMPATIBLE' as ProtocolType,
  baseUrl: '',
  apiKey: ''
})

const formRules: FormRules = {
  providerCode: [
    { required: true, message: '请输入 Provider Code', trigger: 'blur' },
    { pattern: /^[a-z0-9][a-z0-9-]{1,63}$/, message: '全小写字母数字中划线，长度 2-64', trigger: 'blur' }
  ],
  providerName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }],
  protocolType: [{ required: true, message: '请选择协议类型', trigger: 'change' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }]
}

function protocolLabel(type: ProtocolType): string {
  return PROTOCOL_OPTIONS.find(o => o.value === type)?.label || type
}

/** 计费类型展示标签：无值兜底按量付费（与后端 toResponse 口径一致）。 */
function billingLabel(type?: string): string {
  const value = type && !/^\s*$/.test(type) ? type : 'API_KEY'
  return BILLING_TYPE_OPTIONS.find(o => o.value === value)?.label?.replace(/（敬请期待）$/, '') || value
}

// ---- 添加模型两步式弹窗（V59）----
const pickerVisible = ref(false)
const addModelVisible = ref(false)
const addModelInitial = ref<CatalogProvider | null>(null)
const verifyingKeyId = ref<number | null>(null)
// 博查 Key 手动验证 loading（与供应商验证同源：按钮转圈 + 过程消息提示）
const verifyingWebSearchKey = ref(false)

function openPickerDialog() {
  pickerVisible.value = true
}

/** 第一步选中供应商 → 进入第二步表单。 */
function handlePickProvider(entry: CatalogProvider) {
  addModelInitial.value = entry
  addModelVisible.value = true
}

/** 第二步保存完成：刷新列表并定位到新/更新的 Provider。 */
async function handleModelAdded(providerId: number) {
  await loadProviders()
  selectedId.value = providerId
}

/** Provider Key 连通性验证（详情页「验证 Key」与保存后自动验证共用）。 */
async function verifyProviderKey(providerId: number) {
  verifyingKeyId.value = providerId
  const loading = ElMessage({ message: '正在验证 API Key（最小请求探测）…', type: 'info', duration: 0 })
  try {
    const res = await settingsApi.verifyLlmProviderApiKey(providerId)
    if (res.success) {
      ElMessage.success(res.message)
    } else {
      ElMessage({ message: res.message, type: 'error', duration: 8000 })
    }
  } catch (e: any) {
    ElMessage.error('验证请求失败')
  } finally {
    loading.close()
    verifyingKeyId.value = null
  }
}

/** 博查 Key 验证（保存设置后自动调用 / 详情页「验证 Key」按钮手动触发；供应商不支持时降级为提示）。 */
async function verifyWebSearchKey(manual = false) {
  verifyingWebSearchKey.value = manual
  const loading = manual
    ? ElMessage({ message: '正在验证博查 API Key（最小搜索请求探测）…', type: 'info', duration: 0 })
    : null
  try {
    const res = await settingsApi.verifyWebSearchApiKey()
    if (res.supported === false) {
      ElMessage.info(res.message)
    } else if (res.success) {
      ElMessage.success(res.message)
    } else {
      ElMessage({ message: res.message, type: 'error', duration: 8000 })
    }
  } catch (e: any) {
    ElMessage.error('博查 Key 验证请求失败')
  } finally {
    loading?.close()
    verifyingWebSearchKey.value = false
  }
}

/**
 * 质量门控开关确认：§6.151 起默认开启，开启方向是恢复默认（仍二次确认防误点）；
 * 关闭为收敛动作直接放行。before-change 返回 false 时开关保持原值，不污染表单脏状态。
 */
function beforeQualityGateChange(): Promise<boolean> {
  if (!form.qualityGateEnabled) {
    return ElMessageBox.confirm(
      '开启后将开放质量看板及画像重算、自动派发等管理侧入口（默认开启状态）。确认开启？',
      '开启质量门控',
      { confirmButtonText: '开启', cancelButtonText: '取消', type: 'warning' }
    ).then(() => true).catch(() => false)
  }
  return Promise.resolve(true)
}

/**
 * 卸载未保存改动：把基础配置/博查 Key 三个字段恢复到加载快照，
 * LLM Provider / 模型变更属独立保存路径，不参与此处的"未保存"语义。
 */
function handleReset() {
  if (!isDirty.value) return
  form.externalUrl = loadedExternalUrl.value
  form.qualityGateEnabled = loadedQualityGate.value
  form.webSearchApiKey = webSearchKeyLoaded.value
  ElMessage.success('已重置为上次保存的版本')
}

/** 供应商列表键盘漫游：Enter/空格选中，↑↓ 在同级 option 间移动焦点。 */
function onProviderKeydown(e: KeyboardEvent, idx: number) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    selectedId.value = providers.value[idx].id
    return
  }
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  e.preventDefault()
  const next = e.key === 'ArrowDown'
    ? Math.min(idx + 1, providers.value.length - 1)
    : Math.max(idx - 1, 0)
  const list = (e.currentTarget as HTMLElement).parentElement
  list?.querySelectorAll<HTMLElement>('.provider-item')[next]?.focus()
}

/** 通用复制到剪贴板，回退到旧 execCommand 兼容 HTTPS 外的环境。 */
async function copyText(text: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

async function load() {
  try {
    const config = await settingsApi.getConfig()
    if (config) {
      form.externalUrl = config['helloai.base-url'] || ''
      form.qualityGateEnabled = config['admin.quality.enabled'] === 'true'
      form.webSearchApiKey = config['web-search.bocha.api-key'] || ''
      loadedExternalUrl.value = form.externalUrl
      loadedQualityGate.value = form.qualityGateEnabled
      webSearchKeyLoaded.value = form.webSearchApiKey
      webSearchKeyConfigured.value = !!form.webSearchApiKey
    }
  } catch (e: any) {
    ElMessage.error('加载配置失败')
  }
}

async function loadProviders() {
  providersLoading.value = true
  try {
    providers.value = (await settingsApi.listLlmProviders()) || []
    if (!selectedId.value && providers.value.length > 0) {
      // 默认选第一个启用的 provider
      const firstEnabled = providers.value.find(p => p.enabled === 1)
      selectedId.value = (firstEnabled || providers.value[0]).id
    } else if (selectedId.value && !providers.value.find(p => p.id === selectedId.value)) {
      selectedId.value = providers.value.length > 0 ? providers.value[0].id : null
    }
  } catch (e: any) {
    providers.value = []
    ElMessage.error('加载 LLM Provider 列表失败')
  } finally {
    providersLoading.value = false
  }
}

/** 加载当前选中 Provider 的模型列表（启用模型默认勾选）。 */
async function loadModels() {
  if (!selectedId.value) {
    providerModels.value = []
    checkedModels.value = []
    selectedDefaultModel.value = ''
    return
  }
  try {
    const list = (await settingsApi.listProviderModels(selectedId.value)) || []
    providerModels.value = list
    checkedModels.value = list.filter(m => m.enabled === 1).map(m => m.modelName)
    selectedDefaultModel.value = list.find(m => m.isDefault === 1)?.modelName || ''
  } catch (e: any) {
    providerModels.value = []
    checkedModels.value = []
    selectedDefaultModel.value = ''
    ElMessage.error('加载模型列表失败')
  }
}

function selectAllModels() {
  checkedModels.value = providerModels.value.map(m => m.modelName)
}

/** 添加自定义模型（仅自定义 Provider；回车或点击添加）。 */
function addCustomModel() {
  const name = customModelInput.value?.trim()
  if (!name) return
  if (providerModels.value.some(m => m.modelName === name)) {
    ElMessage.warning('模型已存在')
    customModelInput.value = ''
    return
  }
  providerModels.value.push({
    id: 0,
    modelName: name,
    isDefault: 0,
    enabled: 1,
    sortOrder: 100
  })
  checkedModels.value = [...checkedModels.value, name]
  customModelInput.value = ''
}

async function handleSaveModels() {
  if (!selectedId.value) return
  if (!checkedModels.value.length) {
    ElMessage.warning('请至少选择一个可用模型')
    return
  }
  if (!selectedDefaultModel.value || !checkedModels.value.includes(selectedDefaultModel.value)) {
    ElMessage.warning('默认模型必须在已选模型列表中')
    return
  }
  modelSaving.value = true
  try {
    await settingsApi.saveAllProviderModels(selectedId.value, {
      modelNames: checkedModels.value,
      defaultModel: selectedDefaultModel.value
    })
    ElMessage.success('模型配置已保存')
    await loadModels()
    await loadProviders()
  } catch (e: any) {
    ElMessage.error('保存失败')
  } finally {
    modelSaving.value = false
  }
}

async function handleSave() {
  saving.value = true
  try {
    await settingsApi.batchUpdateConfig({
      'helloai.base-url': form.externalUrl,
      'admin.quality.enabled': form.qualityGateEnabled ? 'true' : 'false'
    })
    // 博查 Key 走专用加密端点；未改动（仍是脱敏回显值）时跳过，清空则视为移除。
    if (form.webSearchApiKey !== webSearchKeyLoaded.value) {
      const newKey = form.webSearchApiKey.trim()
      await settingsApi.saveWebSearchApiKey(newKey)
      webSearchKeyConfigured.value = !!newKey
      webSearchKeyLoaded.value = ''
      form.webSearchApiKey = ''
      // 非空保存后自动验证（最小搜索请求探测）
      if (newKey) {
        await verifyWebSearchKey()
      }
    }
    loadedExternalUrl.value = form.externalUrl
    loadedQualityGate.value = form.qualityGateEnabled
    ElMessage.success('保存成功')
  } catch (e: any) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

function openKeyDialog(row: LlmProviderResponse) {
  keyDialogProvider.value = row
  newApiKey.value = ''
  keyDialogVisible.value = true
}

async function handleSaveKey() {
  if (!newApiKey.value || !newApiKey.value.trim()) {
    ElMessage.warning('请输入 API Key')
    return
  }
  if (!keyDialogProvider.value) return
  const providerId = keyDialogProvider.value.id
  try {
    // 后端 /api/admin/llm-providers/{id}/api-key 接收纯字符串，request.ts 默认按 JSON 提交会多包一层引号；
    // 这里用 settingsApi 中显式声明 Content-Type: text/plain 的版本。
    await settingsApi.saveLlmProviderApiKey(providerId, newApiKey.value.trim())
    ElMessage.success('API Key 已生效，无需重启')
    keyDialogVisible.value = false
    await loadProviders()
    // 保存后自动验证连通性（最小请求探测）
    await verifyProviderKey(providerId)
  } catch (e: any) {
    ElMessage.error('保存失败')
  }
}

function openCreateDialog() {
  formDialogMode.value = 'create'
  formDraft.providerCode = ''
  formDraft.providerName = ''
  formDraft.protocolType = 'OPENAI_COMPATIBLE'
  formDraft.baseUrl = ''
  formDraft.apiKey = ''
  formDialogVisible.value = true
  formDialogRef.value?.clearValidate()
}

function openEditDialog(row: LlmProviderResponse) {
  formDialogMode.value = 'edit'
  formDraft.providerCode = row.providerCode
  formDraft.providerName = row.providerName
  formDraft.protocolType = row.protocolType
  formDraft.baseUrl = row.baseUrl || ''
  formDraft.apiKey = ''
  formDialogVisible.value = true
  formDialogRef.value?.clearValidate()
}

async function handleSubmitForm() {
  if (!formDialogRef.value) return
  try {
    await formDialogRef.value.validate()
  } catch {
    return
  }
  try {
    if (formDialogMode.value === 'create') {
      const payload: CreateLlmProviderRequest = {
        providerCode: formDraft.providerCode.trim(),
        providerName: formDraft.providerName.trim(),
        protocolType: formDraft.protocolType,
        baseUrl: formDraft.baseUrl.trim(),
        enabled: 1
      }
      const created = await settingsApi.createLlmProvider(payload)
      if (formDraft.apiKey && formDraft.apiKey.trim()) {
        await settingsApi.saveLlmProviderApiKey(created.id, formDraft.apiKey.trim())
      }
      // 自动选中新 Provider，便于直接配置模型
      selectedId.value = created.id
      ElMessage.success('供应商已添加')
    } else {
      // 编辑：定位当前选中 id
      const id = selectedId.value
      if (!id) return
      await settingsApi.updateLlmProvider(id, {
        providerName: formDraft.providerName.trim(),
        protocolType: formDraft.protocolType,
        baseUrl: formDraft.baseUrl.trim()
      })
      ElMessage.success('已更新')
    }
    formDialogVisible.value = false
    await loadProviders()
  } catch (e: any) {
    ElMessage.error('保存失败')
  }
}

async function handleToggle(row: LlmProviderResponse) {
  try {
    await settingsApi.toggleLlmProvider(row.id)
    ElMessage.success(row.enabled === 1 ? '已禁用' : '已启用')
    await loadProviders()
  } catch (e: any) {
    ElMessage.error('操作失败')
  }
}

async function handleDelete(row: LlmProviderResponse) {
  try {
    await ElMessageBox.confirm(
      `确认删除自定义供应商 "${row.providerName}"（code=${row.providerCode}）？该操作不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await settingsApi.deleteLlmProvider(row.id)
    ElMessage.success('已删除')
    selectedId.value = providers.value.length > 0 ? providers.value[0].id : null
    await loadProviders()
  } catch (e: any) {
    ElMessage.error('删除失败')
  }
}

onMounted(() => {
  load()
  loadProviders()
})
</script>

<style scoped>
/* ============================================================
   页面外壳：Hero + Stack + Save Footer
   ============================================================ */
.settings-page {
  max-width: 1200px;
  margin: 0 auto;
}

/* 顶部 Hero ===================================================== */
.page-hero {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 0 20px;
}
.page-hero-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--ha-radius-md);
  background: var(--ha-primary-muted);
  color: var(--ha-primary);
  flex-shrink: 0;
}
.page-hero-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ha-ink-secondary);
  line-height: 1.5;
}

/* 主包裹卡 settings-sheet ============================================= */
.settings-sheet {
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-lg);
  background: var(--ha-surface);
  box-shadow: var(--ha-shadow-sm);
  overflow: hidden;
}
.settings-sheet :deep(.el-card__body) {
  padding: 0;
}
/* 主卡标题区 */
.sheet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px 18px;
  border-bottom: 1px solid var(--ha-border-light);
}
.sheet-header-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.sheet-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--ha-ink);
  letter-spacing: -0.02em;
  text-wrap: balance;
}
.sheet-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ha-ink-secondary);
  line-height: 1.5;
}

/* 表单容器：去掉 960px 限制，跟随主卡宽度 */
.settings-form {
  width: 100%;
}
.settings-form :deep(.el-form-item) {
  margin-bottom: 18px;
}
.settings-form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}
.settings-stack {
  display: flex;
  flex-direction: column;
}

/* 章节区块：主卡内的分隔区块，仅用编号 chip + 细底边区分，不做紫线悬停提示 */
.section-block {
  padding: 22px 24px;
  border-bottom: 1px solid var(--ha-border-light);
}
.section-block:last-child {
  border-bottom: none;
}

/* 章节标题行：编号 + 标题 + 右侧操作区 */
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 14px;
  margin-bottom: 18px;
  border-bottom: 1px solid var(--ha-border-light);
}
.section-mark {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.section-mark-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: var(--ha-radius-sm);
  background: var(--ha-primary-muted);
  color: var(--ha-primary);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.section-mark-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ha-ink);
  letter-spacing: -0.01em;
  text-wrap: balance;
}

/* 表单提示文案 ==================================================== */
.form-hint {
  font-size: 13px;
  color: var(--ha-ink-secondary);
  line-height: 1.55;
  margin-top: 6px;
}
.form-hint code {
  font-family: var(--ha-font-mono);
  background: var(--ha-surface-hover);
  color: var(--ha-ink-secondary);
  padding: 1px 6px;
  border-radius: var(--ha-radius-sm);
  font-size: 12px;
}
.form-hint .hint-link {
  color: var(--ha-primary);
  text-decoration: none;
}
.form-hint .hint-link:hover {
  text-decoration: underline;
}
.form-hint .hint-tag {
  margin-left: 6px;
  vertical-align: middle;
}

/* 开关文字态（亮/暗双主题同色板） ================================ */
.switch-field {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.switch-state {
  font-size: 13px;
  font-weight: 500;
  color: var(--ha-ink-secondary);
}
.switch-state.on {
  color: var(--ha-success-text);
}

/* 通知复选框 ===================================================== */
.notify-checkbox {
  pointer-events: none;
}
.notify-checkbox :deep(.el-checkbox__label) {
  color: var(--ha-ink);
}
.notify-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 博查 Key 未保存提示 ============================================= */
.verify-key-tip {
  font-size: 12px;
  color: var(--ha-ink-secondary);
  opacity: 0.75;
  margin-top: 6px;
}

/* LLM 供应商：左列表 + 右详情 ==================================== */
.provider-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  min-height: 280px;
}
.provider-list {
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-md);
  padding: 8px;
  background: var(--ha-surface-hover);
  max-height: 420px;
  overflow-y: auto;
}
.provider-item {
  padding: 10px 12px;
  border-radius: var(--ha-radius-md);
  cursor: pointer;
  margin-bottom: 4px;
  transition: background var(--ha-duration-fast) var(--ha-ease-out),
              border-color var(--ha-duration-fast) var(--ha-ease-out);
  border: 1px solid transparent;
}
.provider-item:hover {
  background: var(--ha-surface);
  border-color: var(--ha-border-light);
}
/* 键盘漫游焦点环：与按钮 :focus-visible 先例对齐 */
.provider-item:focus-visible {
  outline: 2px solid var(--ha-primary);
  outline-offset: 2px;
}
.provider-item.active {
  background: var(--ha-primary-light);
  border-color: var(--ha-primary-muted);
}
.provider-item-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  color: var(--ha-ink);
}
.provider-item-code {
  font-size: 12px;
  color: var(--ha-ink-secondary);
  margin-top: 2px;
  padding-left: 22px;
  font-family: var(--ha-font-mono);
}
.status-dot {
  font-size: 14px;
}
.status-dot.on { color: var(--ha-success); }
.status-dot.off { color: var(--ha-muted); }
.tag-builtin {
  margin-left: auto;
}

.provider-detail {
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-md);
  padding: 18px 20px;
  background: var(--ha-surface-elevated);
  box-shadow: var(--ha-shadow-sm);
}
.provider-detail.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ha-surface-hover);
  box-shadow: none;
}

/* 详情头部：Provider logo + 标题 + 操作区 ========================= */
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--ha-border-light);
}
.detail-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ha-ink);
  text-wrap: balance;
}
.detail-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--ha-radius-sm);
  background: var(--ha-primary-muted);
  color: var(--ha-primary);
  flex-shrink: 0;
}
.detail-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.detail-desc :deep(.el-descriptions__label) {
  width: 100px;
  color: var(--ha-ink-secondary);
  font-weight: 500;
}
.detail-desc :deep(.el-descriptions__content) {
  color: var(--ha-ink);
}
.detail-desc :deep(code) {
  font-family: var(--ha-font-mono);
  font-size: 12px;
  background: var(--ha-surface-hover);
  padding: 1px 6px;
  border-radius: var(--ha-radius-sm);
  color: var(--ha-ink-secondary);
}

.base-url-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}
.base-url-text {
  font-family: var(--ha-font-mono);
  font-size: 12px;
  word-break: break-all;
  color: var(--ha-ink);
}
.base-url-copy {
  flex-shrink: 0;
}

.detail-hint {
  margin-top: 12px;
}

/* 模型配置区 ====================================================== */
.model-section {
  margin-top: 18px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-md);
  padding: 14px 16px;
  background: var(--ha-surface-hover);
}
.model-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.model-section-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--ha-ink);
}
.model-section-icon {
  color: var(--ha-primary);
}
.model-section-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.model-checkbox-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.model-checkbox {
  width: 100%;
  margin-right: 0 !important;
}
.model-checkbox :deep(.el-checkbox__label) {
  width: 100%;
}
.model-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.model-row-name {
  font-family: var(--ha-font-mono);
  font-size: 13px;
}
.model-row-tag {
  flex-shrink: 0;
}
.custom-model-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
}
.custom-model-input {
  flex: 1;
  min-width: 200px;
}
.default-model-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  flex-wrap: wrap;
}
.default-model-label {
  color: var(--ha-ink-secondary);
  font-size: 14px;
  white-space: nowrap;
}
.key-missing {
  color: var(--ha-warning-text);
}
.key-source {
  margin-left: 6px;
}

/* 主卡底部保存区 sheet-footer ========================================== */
.sheet-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px;
  border-top: 1px solid var(--ha-border-light);
  background: var(--ha-surface-hover);
}
.sheet-footer-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

/* 响应式 =========================================================== */
@media (max-width: 768px) {
  .settings-form :deep(.el-form-item__label) {
    width: auto !important;
    padding-bottom: 4px;
  }
  .provider-layout {
    grid-template-columns: 1fr;
  }
  .detail-actions {
    width: 100%;
  }
  .sheet-header {
    flex-wrap: wrap;
  }
  .section-block {
    padding: 18px 16px;
  }
  .sheet-footer {
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .sheet-footer-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>