/**
 * 预置 LLM 供应商目录（「添加模型」弹窗第一步）。
 *
 * 与 V46 迁移种子到 llm_provider 的 builtin 行对应，
 * providerCode 为唯一关联键：选中预置项 = 为既有内置供应商写入/覆盖 API Key；
 * 目录之外选择「自定义供应商」才走 createLlmProvider 新建流程。
 */
import type { ProtocolType } from '@/api/settings'

/** 目录条目：展示元数据 + 新建自定义供应商时的缺省端点配置。 */
export interface CatalogProvider {
  providerCode: string
  providerName: string
  /** 徽标文字（品牌首字母，monogram 风格，不引外部 logo）。 */
  monogram: string
  /** 品牌主色（徽标底色）。 */
  brandColor: string
  /** 卡片副标题（一句话说明）。 */
  tagline: string
  protocolType: ProtocolType
  baseUrl: string
  defaultModel: string
  /** 官方 API Key 获取页（表单「获取 API 密钥」链接）。 */
  apiKeyUrl: string
}

export const PROVIDER_CATALOG: CatalogProvider[] = [
  {
    providerCode: 'deepseek',
    providerName: 'DeepSeek',
    monogram: 'DS',
    brandColor: '#4D6BFE',
    tagline: '深度求索 · 高性价比通用大模型',
    protocolType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.deepseek.com',
    defaultModel: 'deepseek-chat',
    apiKeyUrl: 'https://platform.deepseek.com/api_keys'
  },
  {
    providerCode: 'moonshot',
    providerName: 'Kimi（月之暗面）',
    monogram: 'K',
    brandColor: '#1F2430',
    tagline: 'Moonshot · 长上下文对话模型',
    protocolType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.moonshot.cn/v1',
    defaultModel: 'moonshot-v1-8k',
    apiKeyUrl: 'https://platform.moonshot.cn/console/api-keys'
  },
  {
    providerCode: 'minimax',
    providerName: 'MiniMax',
    monogram: 'MM',
    brandColor: '#2F54EB',
    tagline: '稀宇 · 通用文本大模型',
    protocolType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.minimaxi.com/v1',
    defaultModel: 'MiniMax-Text-01',
    apiKeyUrl: 'https://platform.minimaxi.com/user-center/basic-information/interface-key'
  },
  {
    providerCode: 'dashscope',
    providerName: '通义千问',
    monogram: 'QW',
    brandColor: '#FF6A00',
    tagline: '阿里云百炼 · Qwen 系列模型',
    protocolType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    defaultModel: 'qwen-turbo',
    apiKeyUrl: 'https://bailian.console.aliyun.com/?apiKey=1#/api-key'
  }
]

/** 按 providerCode 查目录条目；未命中返回 null（自定义供应商）。 */
export function findCatalogProvider(providerCode: string): CatalogProvider | null {
  return PROVIDER_CATALOG.find(p => p.providerCode === providerCode) || null
}
