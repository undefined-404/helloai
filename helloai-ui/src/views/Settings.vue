<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <span>系统设置</span>
      </template>
      <el-form ref="formRef" :model="form" label-width="140px" class="settings-form">
        <el-divider content-position="left">基础配置</el-divider>
        <el-form-item label="平台名称">
          <el-input v-model="form.platformName" />
        </el-form-item>
        <el-form-item label="外部访问地址">
          <el-input v-model="form.externalUrl" placeholder="http://192.168.1.100:6565" />
          <div class="form-hint">
            用于生成 SKILL 接入内容（Agent 凭此地址回连本平台）。
            本机可直接用 <code>http://localhost:6565</code>，其他设备用
            <code>http://&lt;本机IP&gt;:6565</code>，公网部署用域名或公网 IP。
          </div>
        </el-form-item>

        <el-divider content-position="left">
          LLM 供应商
          <el-button type="primary" size="small" :icon="Plus" class="header-action"
            @click="openCreateDialog">添加供应商</el-button>
        </el-divider>

        <div class="provider-layout">
          <!-- 左侧列表 -->
          <div class="provider-list">
            <div v-for="p in providers" :key="p.id"
              class="provider-item" :class="{ active: selectedId === p.id }"
              @click="selectedId = p.id">
              <div class="provider-item-name">
                <el-icon v-if="p.enabled === 1" class="status-dot on"><CircleCheckFilled /></el-icon>
                <el-icon v-else class="status-dot off"><CircleCloseFilled /></el-icon>
                <span>{{ p.providerName }}</span>
                <el-tag v-if="p.builtin === 1" type="info" size="small" class="tag-builtin">内置</el-tag>
              </div>
              <div class="provider-item-code">{{ p.providerCode }}</div>
            </div>
            <el-empty v-if="!providersLoading && providers.length === 0"
              description="还没有配置任何 LLM Provider" :image-size="64" />
          </div>

          <!-- 右侧详情 -->
          <div class="provider-detail" v-if="selectedProvider">
            <div class="detail-header">
              <h3 class="detail-title">
                {{ selectedProvider.providerName }}
                <el-tag v-if="selectedProvider.builtin === 1" type="info" size="small">内置</el-tag>
              </h3>
              <div class="detail-actions">
                <el-button v-if="selectedProvider.builtin !== 1" size="small" type="primary" plain
                  @click="openEditDialog(selectedProvider)">编辑</el-button>
                <el-button size="small" type="primary" plain
                  @click="openKeyDialog(selectedProvider)">配置 Key</el-button>
                <el-button v-if="selectedProvider.builtin !== 1" size="small" plain
                  @click="handleToggle(selectedProvider)">
                  {{ selectedProvider.enabled === 1 ? '禁用' : '启用' }}
                </el-button>
                <el-button v-if="selectedProvider.builtin !== 1" size="small" type="danger" plain
                  @click="handleDelete(selectedProvider)">删除</el-button>
              </div>
            </div>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="协议">
                {{ protocolLabel(selectedProvider.protocolType) }}
              </el-descriptions-item>
              <el-descriptions-item label="Provider Code">
                <code>{{ selectedProvider.providerCode }}</code>
              </el-descriptions-item>
              <el-descriptions-item label="Base URL">
                {{ selectedProvider.baseUrl || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="默认模型">
                {{ selectedProvider.defaultModel || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="API Key">
                <span v-if="selectedProvider.apiKeyConfigured">{{ selectedProvider.apiKeyMasked }}</span>
                <span v-else class="key-missing">未配置</span>
                <el-tag v-if="selectedProvider.apiKeyFromVault" type="success" size="small" class="key-source">
                  vault</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag v-if="selectedProvider.enabled === 1" type="success" size="small">已启用</el-tag>
                <el-tag v-else type="info" size="small">已禁用</el-tag>
              </el-descriptions-item>
            </el-descriptions>
            <div class="detail-hint">
              <el-alert type="info" :closable="false" show-icon>
                <template #title>
                  启用后才能在 Agent 注册时被选为默认 provider；禁用仅是管理侧的"软隐藏"，不会删除任何 Agent。
                </template>
              </el-alert>
            </div>
          </div>
          <div class="provider-detail placeholder" v-else>
            <el-empty description="左侧选择一个 Provider 查看详情" :image-size="64" />
          </div>
        </div>

        <el-divider content-position="left">通知配置</el-divider>
        <el-form-item label="通知方式">
          <el-checkbox-group v-model="form.notifyChannels">
            <el-checkbox label="web" disabled>站内通知</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSave">保存设置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 配置 API Key 对话框 -->
    <el-dialog v-model="keyDialogVisible" :title="'配置 API Key：' + (keyDialogProvider?.providerName || '')" width="480px">
      <el-input v-model="newApiKey" type="password" show-password
        placeholder="输入新 API Key（将覆盖旧 Key，实时生效无需重启）" />
      <template #footer>
        <el-button @click="keyDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveKey">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加 / 编辑 Provider 对话框 -->
    <el-dialog v-model="formDialogVisible" :title="formDialogMode === 'create' ? '添加 LLM 供应商' : '编辑 LLM 供应商'" width="560px">
      <el-form ref="formDialogRef" :model="formDraft" :rules="formRules" label-width="110px">
        <el-form-item label="Provider Code" prop="providerCode">
          <el-input v-model="formDraft.providerCode" placeholder="小写字母数字中划线，如 my-openai" :disabled="formDialogMode === 'edit'" />
          <div class="form-hint">唯一标识，创建后不可修改（内置 Provider 不可改）</div>
        </el-form-item>
        <el-form-item label="显示名称" prop="providerName">
          <el-input v-model="formDraft.providerName" placeholder="如 我的 OpenAI" />
        </el-form-item>
        <el-form-item label="协议类型" prop="protocolType">
          <el-select v-model="formDraft.protocolType" style="width: 100%">
            <el-option v-for="opt in PROTOCOL_OPTIONS" :key="opt.value"
              :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="formDraft.baseUrl" placeholder="如 https://api.openai.com/v1" />
        </el-form-item>
        <el-form-item label="默认模型">
          <el-input v-model="formDraft.defaultModel" placeholder="如 gpt-4o-mini（可选）" />
        </el-form-item>
        <el-form-item v-if="formDialogMode === 'create'" label="API Key">
          <el-input v-model="formDraft.apiKey" type="password" show-password
            placeholder="新增时可一并填写；也可稍后单独配置" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox, FormInstance, FormRules } from 'element-plus'
import { Plus, CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import {
  settingsApi,
  LlmProviderResponse,
  CreateLlmProviderRequest,
  ProtocolType,
  PROTOCOL_OPTIONS
} from '@/api/settings'

const formRef = ref()
const form = reactive({
  platformName: 'HelloAI',
  externalUrl: '',
  notifyChannels: ['web']
})

const providers = ref<LlmProviderResponse[]>([])
const providersLoading = ref(false)
const selectedId = ref<number | null>(null)

const selectedProvider = computed<LlmProviderResponse | null>(
  () => providers.value.find(p => p.id === selectedId.value) || null
)

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
  defaultModel: '',
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

async function load() {
  try {
    const config = await settingsApi.getConfig()
    if (config) {
      form.platformName = config['system.name'] || 'HelloAI'
      form.externalUrl = config['helloai.base-url'] || ''
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

async function handleSave() {
  try {
    await settingsApi.batchUpdateConfig({
      'system.name': form.platformName,
      'helloai.base-url': form.externalUrl
    })
    ElMessage.success('保存成功')
  } catch (e: any) {
    ElMessage.error('保存失败')
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
  try {
    // 后端 /api/admin/llm-providers/{id}/api-key 接收纯字符串，request.ts 默认按 JSON 提交会多包一层引号；
    // 这里用 settingsApi 中显式声明 Content-Type: text/plain 的版本。
    await settingsApi.saveLlmProviderApiKey(keyDialogProvider.value.id, newApiKey.value.trim())
    ElMessage.success('API Key 已生效，无需重启')
    keyDialogVisible.value = false
    await loadProviders()
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
  formDraft.defaultModel = ''
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
  formDraft.defaultModel = row.defaultModel || ''
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
        defaultModel: formDraft.defaultModel?.trim() || undefined,
        enabled: 1
      }
      const created = await settingsApi.createLlmProvider(payload)
      if (formDraft.apiKey && formDraft.apiKey.trim()) {
        await settingsApi.saveLlmProviderApiKey(created.id, formDraft.apiKey.trim())
      }
      ElMessage.success('供应商已添加')
    } else {
      // 编辑：定位当前选中 id
      const id = selectedId.value
      if (!id) return
      await settingsApi.updateLlmProvider(id, {
        providerName: formDraft.providerName.trim(),
        protocolType: formDraft.protocolType,
        baseUrl: formDraft.baseUrl.trim(),
        defaultModel: formDraft.defaultModel?.trim() || ''
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
.page { max-width: 1200px; }
.settings-form {
  max-width: 920px;
}
.form-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
}
.form-hint code {
  background: #f5f7fa;
  padding: 1px 4px;
  border-radius: 3px;
}

.provider-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  margin-bottom: 16px;
  min-height: 280px;
}
.provider-list {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px;
  background: #fafbfc;
  max-height: 420px;
  overflow-y: auto;
}
.provider-item {
  padding: 8px 10px;
  border-radius: 4px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.15s;
  border: 1px solid transparent;
}
.provider-item:hover {
  background: #ecf5ff;
}
.provider-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}
.provider-item-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  color: #303133;
}
.provider-item-code {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  padding-left: 18px;
}
.status-dot {
  font-size: 14px;
}
.status-dot.on { color: #67c23a; }
.status-dot.off { color: #c0c4cc; }
.tag-builtin {
  margin-left: auto;
}

.provider-detail {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 16px;
  background: #fff;
}
.provider-detail.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafbfc;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}
.detail-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.detail-actions {
  display: flex;
  gap: 8px;
}
.detail-hint {
  margin-top: 12px;
}
.key-missing {
  color: #e6a23c;
}
.key-source {
  margin-left: 6px;
}

.header-action {
  margin-left: 12px;
}

@media (max-width: 768px) {
  .provider-layout {
    grid-template-columns: 1fr;
  }
  .settings-form {
    max-width: 100%;
  }
  .settings-form :deep(.el-form-item__label) {
    width: auto !important;
    padding-bottom: 0;
  }
}
</style>
