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

export const settingsApi = {
  getStatus() {
    return request.get<any, { setupFinished: boolean; hasUsers: boolean; userCount: number }>(paths.setup.getStatus)
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
  /** 写入 API Key（请求体为纯字符串的 apiKey 明文）。 */
  saveLlmProviderApiKey(id: number, apiKey: string) {
    return request.put(paths.admin.llmProviderApiKey(id), apiKey, {
      headers: { 'Content-Type': 'text/plain' }
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
