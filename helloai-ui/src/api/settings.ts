import request from './request'
import { paths } from './paths'

/** 平台级 LLM Provider 配置项（旧 /api/admin/platform/providers/list 响应，向后兼容）。 */
export interface ProviderConfigItem {
  name: string
  defaultModel?: string
  baseUrl?: string
  apiKeyConfigured: boolean
  apiKeyMasked?: string
  available: boolean
  apiKeyFromVault: boolean
}

/** 协议类型枚举（方案B）。 */
export type ProtocolType = 'OPENAI_COMPATIBLE' | 'ANTHROPIC_COMPATIBLE'

/** 平台级 LLM Provider 详情 / 列表项（方案B，对应 /api/admin/llm-providers/*）。 */
export interface LlmProviderResponse {
  id: number
  providerCode: string
  providerName: string
  protocolType: ProtocolType
  baseUrl?: string
  defaultModel?: string
  enabled: number
  builtin: number
  sortOrder: number
  /** 计费类型：API_KEY=按量付费（默认）；TOKEN_PLAN / CODING_PLAN 预留暂不可用。 */
  billingType?: string
  extraConfig?: Record<string, any>
  apiKeyConfigured: boolean
  apiKeyMasked?: string
  apiKeyFromVault: boolean
}

/** 新增 LLM Provider 请求体。 */
export interface CreateLlmProviderRequest {
  providerCode: string
  providerName: string
  protocolType: ProtocolType
  baseUrl: string
  defaultModel?: string
  enabled?: number
  sortOrder?: number
  /** 计费类型（不传后端兜底 API_KEY）。 */
  billingType?: string
  extraConfig?: Record<string, any>
}

/** 修改 LLM Provider 请求体（局部更新）。 */
export interface UpdateLlmProviderRequest {
  providerName?: string
  protocolType?: ProtocolType
  baseUrl?: string
  defaultModel?: string
  enabled?: number
  sortOrder?: number
  billingType?: string
  extraConfig?: Record<string, any>
}

/** LLM Provider 模型项（V49，对应 /api/admin/llm-providers/{id}/models/list）。 */
export interface LlmProviderModelResponse {
  id: number
  modelName: string
  isDefault: number
  enabled: number
  sortOrder: number
}

/** 批量保存 Provider 模型配置请求体（saveAll）。 */
export interface SaveProviderModelsRequest {
  modelNames: string[]
  defaultModel: string
}

/** 协议类型选项（前端下拉）。 */
export const PROTOCOL_OPTIONS: { label: string; value: ProtocolType }[] = [
  { label: 'OpenAI 兼容', value: 'OPENAI_COMPATIBLE' },
  { label: 'Anthropic 兼容', value: 'ANTHROPIC_COMPATIBLE' }
]

/** 计费类型选项（前端下拉）：当前仅按量付费可用，其余预留置灰。 */
export const BILLING_TYPE_OPTIONS: { label: string; value: string; disabled: boolean }[] = [
  { label: '按量付费（API Key）', value: 'API_KEY', disabled: false },
  { label: 'Token Plan（敬请期待）', value: 'TOKEN_PLAN', disabled: true },
  { label: 'Coding Plan（敬请期待）', value: 'CODING_PLAN', disabled: true }
]

/** API Key 验证结果（后端 verifyApiKeyById / verifyWebSearchApiKey 响应）。 */
export interface ApiKeyVerifyResult {
  success: boolean
  message: string
  model?: string
  supported?: boolean
  elapsedMs?: number
}

/**
 * API Key 保存结果（`M-6`，后端 saveApiKeyById / saveApiKeyByProvider 响应）。
 * `created=false` 表示同 provider 下该 Key 已存在（未重复保存）。
 */
export interface ApiKeySaveResult {
  created: boolean
  /** Key 指纹（SHA-256 前缀，24 字符）；不可逆，不暴露明文。 */
  fingerprint?: string
  message?: string
}

/**
 * API Key **批量**保存结果（`M-7`，后端 saveApiKeysById 响应）。
 *
 * - `added` / `alreadyExists` 构成验收口径文案「新增 N 把 / 已存在 M 把」；
 * - `primaryActivated=true` 表示该供应商此前无启用凭证，本批首把已生效；
 *   否则新增的 Key 全部落为**备位**（不覆盖正在使用的主 Key），转正属 `M-8`。
 */
export interface ApiKeyBatchSaveResult {
  added: number
  alreadyExists: number
  primaryActivated: boolean
  /** 新落库各把的指纹（24 字符，不可逆），顺序与输入一致。 */
  addedFingerprints?: string[]
  message?: string
}

/** `M-8` Key 列表的一行（不含密文）。 */
export interface LlmProviderApiKeyItem {
  id: number
  /** `ACTIVE`（启用位 / 主）| `DISABLED`（备位）| `EXPIRED`（轮换淘汰的历史行）。 */
  status: 'ACTIVE' | 'DISABLED' | 'EXPIRED'
  /** 是否当前启用位（后端由 status 推导，前端不再重复该判据）。 */
  primary: boolean
  /** 明文指纹（24 字符，不可逆）；切换 / 停用时用它点名。 */
  fingerprint?: string
  /** 脱敏展示值（前 4 + … + 后 4）；secretRef 型或解密失败时为 null。 */
  masked?: string | null
  createTime?: string
  remark?: string
}

/** `M-8` 主备操作结果（切换 / 停用 / 删除）。 */
export interface LlmProviderApiKeyChange {
  credentialId: number
  fingerprint?: string
  previousActiveId?: number | null
  activeId?: number | null
  /** 本次是否改变了「哪一把是启用位」（后端由前后 activeId 推导）。 */
  activeChanged: boolean
}

export const settingsApi = {
  getStatus() {
    return request.get<any, { setupFinished: boolean; hasUsers: boolean; userCount: number; registerEnabled?: boolean }>(paths.setup.getStatus)
  },
  initialize(data: { adminPassword: string; systemName: string; systemDescription?: string; adminUsername?: string }) {
    return request.post(paths.setup.initialize, data)
  },
  getConfig() {
    return request.get<any, Record<string, string>>(paths.admin.config)
  },
  getConfigValue(key: string) {
    return request.get<any, string>(paths.admin.configByKey(key))
  },
  updateConfig(key: string, value: string) {
    return request.put(paths.admin.configUpdateByKey(key), { value })
  },
  /**
   * 批量更新配置（保存"基础配置"区域：平台名 + 外部访问地址）。
   * 后端 ConfigBatchRequest 期待 wrapper 结构 {config:{...}}，前端不能直接发 flat map，
   * 否则 SysConfigService.batchUpdate 拿到 null 会 NPE（2026-08-08 实测 500 复现）。
   */
  batchUpdateConfig(map: Record<string, string>) {
    return request.put(paths.admin.configBatch, { config: map })
  },
  /** 保存博查联网搜索 API Key（后端加密落库，实时生效）。 */
  saveWebSearchApiKey(value: string) {
    return request.put(paths.admin.webSearchApiKey, { value })
  },
  /** 验证博查联网搜索 API Key（最小搜索请求探测）。 */
  verifyWebSearchApiKey() {
    return request.post<any, ApiKeyVerifyResult>(paths.admin.verifyWebSearchApiKey)
  },
  // ---- 旧端点（保留兼容）----
  listProviders() {
    return request.get<any, ProviderConfigItem[]>(paths.admin.platformProviders)
  },
  saveProviderApiKey(provider: string, apiKey: string) {
    return request.put(paths.admin.platformProviderApiKey(provider), { apiKey })
  },
  saveProviderSettings(provider: string, settings: { baseUrl?: string; defaultModel?: string }) {
    return request.put(paths.admin.platformProviderSettings(provider), settings)
  },
  // ---- 新端点（方案B 动态化 LLM Provider）----
  listLlmProviders() {
    return request.get<any, LlmProviderResponse[]>(paths.admin.llmProviders)
  },
  getLlmProvider(id: number) {
    return request.get<any, LlmProviderResponse>(paths.admin.llmProviderById(id))
  },
  createLlmProvider(data: CreateLlmProviderRequest) {
    return request.post<any, LlmProviderResponse>(paths.admin.llmProviderCreate, data)
  },
  updateLlmProvider(id: number, data: UpdateLlmProviderRequest) {
    return request.put(paths.admin.llmProviderUpdate(id), data)
  },
  deleteLlmProvider(id: number) {
    return request.delete(paths.admin.llmProviderDelete(id))
  },
  toggleLlmProvider(id: number) {
    return request.put(paths.admin.llmProviderToggle(id), {})
  },
  /**
   * 写入 API Key（请求体为纯字符串的 apiKey 明文）。
   *
   * @returns `created=false` ⇒ 该 Key 已存在（`M-6` 同 provider 内同指纹去重）
   */
  saveLlmProviderApiKey(id: number, apiKey: string) {
    return request.put<any, ApiKeySaveResult>(paths.admin.llmProviderApiKey(id), apiKey, {
      headers: { 'Content-Type': 'text/plain' }
    })
  },
  /**
   * `M-7` 批量写入 API Key：请求体为纯字符串，可一次粘贴多把
   * （换行 / 逗号 / 分号 / 空格分隔，服务端剥引号 + 输入内去重）。
   *
   * @returns 「新增 N 把 / 已存在 M 把」计数；`primaryActivated` 标明首把是否已生效
   */
  saveLlmProviderApiKeys(id: number, rawText: string) {
    return request.put<any, ApiKeyBatchSaveResult>(paths.admin.llmProviderApiKeys(id), rawText, {
      headers: { 'Content-Type': 'text/plain' }
    })
  },
  // ---- `M-8` 多 Key 主备（手动切换 / 停用 / 删除；无自动 failover）----
  /** Key 列表（主 / 备 / 历史，不含密文；明文仅以 masked 脱敏形态出现）。 */
  listLlmProviderApiKeys(id: number) {
    return request.get<any, LlmProviderApiKeyItem[]>(paths.admin.llmProviderApiKeyList(id))
  },
  /** 手动把指定备位升为启用位（原启用位降为备位）。 */
  activateLlmProviderApiKey(id: number, credentialId: number) {
    return request.put<any, LlmProviderApiKeyChange>(paths.admin.llmProviderApiKeyActivate(id, credentialId))
  },
  /** 停用；停用启用位时后端会自动把最旧备位顶为启用位，无备位可顶则报错。 */
  deactivateLlmProviderApiKey(id: number, credentialId: number) {
    return request.put<any, LlmProviderApiKeyChange>(paths.admin.llmProviderApiKeyDeactivate(id, credentialId))
  },
  /** 删除（软删）；删除启用位时同样先顶替。 */
  deleteLlmProviderApiKey(id: number, credentialId: number) {
    return request.delete<any, LlmProviderApiKeyChange>(paths.admin.llmProviderApiKeyDelete(id, credentialId))
  },
  /** 验证 Provider API Key（最小请求直探端点，耗时最长约 20s）。 */
  verifyLlmProviderApiKey(id: number) {
    return request.post<any, ApiKeyVerifyResult>(paths.admin.llmProviderVerifyApiKey(id), null, {
      timeout: 30000
    })
  },
  // ---- 模型管理（V49，模型多选配置）----
  listProviderModels(id: number) {
    return request.get<any, LlmProviderModelResponse[]>(paths.admin.llmProviderModels(id))
  },
  addProviderModel(id: number, modelName: string, isDefault: boolean) {
    return request.post<any, LlmProviderModelResponse>(paths.admin.llmProviderModelCreate(id), {
      modelName,
      isDefault
    })
  },
  saveAllProviderModels(id: number, data: SaveProviderModelsRequest) {
    return request.put(paths.admin.llmProviderModelsSaveAll(id), data)
  },
  deleteProviderModel(id: number, modelName: string) {
    return request.delete(paths.admin.llmProviderModelDelete(id, modelName))
  },
  toggleProviderModel(id: number, modelName: string, enabled: boolean) {
    return request.put(paths.admin.llmProviderModelToggle(id, modelName), {
      enabled
    })
  },
  setDefaultProviderModel(id: number, modelName: string) {
    return request.put(paths.admin.llmProviderModelSetDefault(id, modelName), {})
  }
}
