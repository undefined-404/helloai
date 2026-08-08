import request from './request'

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

/** 协议类型选项（前端下拉）。 */
export const PROTOCOL_OPTIONS: { label: string; value: ProtocolType }[] = [
  { label: 'OpenAI 兼容', value: 'OPENAI_COMPATIBLE' },
  { label: 'Anthropic 兼容', value: 'ANTHROPIC_COMPATIBLE' }
]

export const settingsApi = {
  getStatus() {
    return request.get<any, { setupFinished: boolean; hasUsers: boolean; userCount: number }>('/setup/getStatus')
  },
  initialize(data: { adminPassword: string; systemName: string; systemDescription?: string; adminUsername?: string }) {
    return request.post('/setup/initialize', data)
  },
  getConfig() {
    return request.get<any, Record<string, string>>('/admin/config')
  },
  getConfigValue(key: string) {
    return request.get<any, string>(`/admin/config/getByKey/${key}`)
  },
  updateConfig(key: string, value: string) {
    return request.put(`/admin/config/updateByKey/${key}`, { value })
  },
  /**
   * 批量更新配置（保存"基础配置"区域：平台名 + 外部访问地址）。
   * 后端 ConfigBatchRequest 期待 wrapper 结构 {config:{...}}，前端不能直接发 flat map，
   * 否则 SysConfigService.batchUpdate 拿到 null 会 NPE（2026-08-08 实测 500 复现）。
   */
  batchUpdateConfig(map: Record<string, string>) {
    return request.put('/admin/config/batch', { config: map })
  },
  // ---- 旧端点（保留兼容）----
  listProviders() {
    return request.get<any, ProviderConfigItem[]>('/admin/platform/providers/list')
  },
  saveProviderApiKey(provider: string, apiKey: string) {
    return request.put(`/admin/platform/providers/${provider}/api-key`, { apiKey })
  },
  saveProviderSettings(provider: string, settings: { baseUrl?: string; defaultModel?: string }) {
    return request.put(`/admin/platform/providers/${provider}/settings`, settings)
  },
  // ---- 新端点（方案B 动态化 LLM Provider）----
  listLlmProviders() {
    return request.get<any, LlmProviderResponse[]>('/admin/llm-providers/list')
  },
  getLlmProvider(id: number) {
    return request.get<any, LlmProviderResponse>(`/admin/llm-providers/getById/${id}`)
  },
  createLlmProvider(data: CreateLlmProviderRequest) {
    return request.post<any, LlmProviderResponse>('/admin/llm-providers', data)
  },
  updateLlmProvider(id: number, data: UpdateLlmProviderRequest) {
    return request.put(`/admin/llm-providers/updateById/${id}`, data)
  },
  deleteLlmProvider(id: number) {
    return request.delete(`/admin/llm-providers/deleteById/${id}`)
  },
  toggleLlmProvider(id: number) {
    return request.put(`/admin/llm-providers/toggleById/${id}`, {})
  },
  /** 写入 API Key（请求体为纯字符串的 apiKey 明文）。 */
  saveLlmProviderApiKey(id: number, apiKey: string) {
    return request.put(`/admin/llm-providers/${id}/api-key`, apiKey, {
      headers: { 'Content-Type': 'text/plain' }
    })
  }
}
