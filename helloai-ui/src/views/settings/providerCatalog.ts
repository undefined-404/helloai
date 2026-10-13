/**
 * 预置 LLM 供应商目录（「添加模型」弹窗第一步）。
 *
 * 与 V46 迁移种子到 llm_provider 的 builtin 行对应，
 * providerCode 为唯一关联键：选中预置项 = 为既有内置供应商写入/覆盖 API Key；
 * 目录之外选择「自定义供应商」才走 createLlmProvider 新建流程。
 *
 * <p><b>★取值口径（三处必须一致，改一处必核另两处）</b>：本目录的
 * {@code protocolType / baseUrl / defaultModel} 是**平台内置供应商的规范注册信息**，
 * 唯一真值来源：
 * <ol>
 *   <li>{@code helloai-start/src/main/resources/application.yml} 的 {@code helloai.providers.*}；</li>
 *   <li>迁移种子与订正：{@code V46}（provider 种子）/ {@code V49}（模型目录）/ {@code V61}（moonshot 下线）/
 *       {@code V101}（defaultModel 与目录默认位对齐）；</li>
 *   <li>{@code llm_provider_model} 中该 provider 的 {@code is_default=1} 模型。</li>
 * </ol>
 * <b>教训（2026-10-13 实测）</b>：本目录曾长期留存 {@code deepseek-chat} / {@code moonshot-v1-8k} /
 * {@code qwen-turbo} / {@code MiniMax-Text-01} 四个陈旧值——其中 {@code moonshot-v1-8k} 已下线（调用 404，
 * 见 V61 注释）为「验证失败」直接根因，{@code deepseek-chat} 已不在模型目录内（见 V101 注释）导致注册被拒。
 * {@code baseUrl} 同理：OpenAI 兼容模式实际端点为 {@code {baseUrl}/v1/chat/completions}
 * （见 {@code application.yml} 的 dashscope 段注释），故 {@code baseUrl} <b>不得</b>自带 {@code /v1} 后缀。
 * </p>
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
    // 目录默认位（llm_provider_model.is_default=1，V101 订正）：旧值 'deepseek-chat' 已不在目录内
    defaultModel: 'deepseek-v4-flash',
    apiKeyUrl: 'https://platform.deepseek.com/api_keys'
  },
  {
    providerCode: 'moonshot',
    providerName: 'Kimi（月之暗面）',
    monogram: 'K',
    brandColor: '#1F2430',
    tagline: 'Moonshot · 长上下文对话模型',
    protocolType: 'OPENAI_COMPATIBLE',
    // 不带 /v1：OpenAI 兼容模式实际端点为 {baseUrl}/v1/chat/completions
    baseUrl: 'https://api.moonshot.cn',
    // moonshot-v1 全系列 2026-08-31 已下线（404，V61 订正）：切目录默认位 kimi-k3
    defaultModel: 'kimi-k3',
    apiKeyUrl: 'https://platform.moonshot.cn/console/api-keys'
  },
  {
    providerCode: 'minimax',
    providerName: 'MiniMax',
    monogram: 'MM',
    brandColor: '#2F54EB',
    tagline: '稀宇 · 通用文本大模型',
    // MiniMax 走 Anthropic 兼容接口（application.yml 与 V46 种子同口径），非 OpenAI 兼容
    protocolType: 'ANTHROPIC_COMPATIBLE',
    baseUrl: 'https://api.minimaxi.com/anthropic',
    // M2.5 / M2.7 全系列即将下线（V108 订正）：切现役 MiniMax-M3（参考 magpie workbuddy.go:91 / factory.go:625）
    defaultModel: 'MiniMax-M3',
    apiKeyUrl: 'https://platform.minimaxi.com/user-center/basic-information/interface-key'
  },
  {
    providerCode: 'dashscope',
    providerName: '通义千问',
    monogram: 'QW',
    brandColor: '#FF6A00',
    tagline: '阿里云百炼 · Qwen 系列模型',
    protocolType: 'OPENAI_COMPATIBLE',
    // 不带 /v1：OpenAI 兼容模式实际端点为 {baseUrl}/v1/chat/completions
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode',
    // 目录默认位（V101 订正）：旧值 'qwen-turbo' 已不在目录内
    defaultModel: 'qwen3.7-plus',
    apiKeyUrl: 'https://bailian.console.aliyun.com/?apiKey=1#/api-key'
  },
  {
    providerCode: 'openai',
    providerName: 'OpenAI',
    monogram: 'AI',
    brandColor: '#10A37F',
    tagline: 'OpenAI · GPT 系列对话模型',
    protocolType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.openai.com',
    defaultModel: 'gpt-4o',
    apiKeyUrl: 'https://platform.openai.com/api-keys'
  },
  {
    providerCode: 'anthropic',
    providerName: 'Anthropic',
    monogram: 'C',
    brandColor: '#D97757',
    tagline: 'Anthropic · Claude 系列模型',
    protocolType: 'ANTHROPIC_COMPATIBLE',
    baseUrl: 'https://api.anthropic.com',
    defaultModel: 'claude-sonnet-4-5',
    apiKeyUrl: 'https://console.anthropic.com/settings/keys'
  },
  {
    providerCode: 'xai',
    providerName: 'xAI',
    monogram: 'G',
    brandColor: '#1A1A1A',
    tagline: 'xAI · Grok 系列模型',
    protocolType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.x.ai',
    defaultModel: 'grok-4.7',
    apiKeyUrl: 'https://console.x.ai'
  },
  {
    providerCode: 'mistral',
    providerName: 'Mistral AI',
    monogram: 'M',
    brandColor: '#FF7000',
    tagline: 'Mistral · 轻量高效生成模型',
    protocolType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.mistral.ai',
    defaultModel: 'mistral-large-latest',
    apiKeyUrl: 'https://console.mistral.ai/api-keys'
  },
  {
    providerCode: 'groq',
    providerName: 'Groq',
    monogram: 'GQ',
    brandColor: '#F55036',
    tagline: 'Groq · 极速推理芯片服务',
    protocolType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.groq.com/openai',
    defaultModel: 'llama-3.3-70b-versatile',
    apiKeyUrl: 'https://console.groq.com/keys'
  }
]

/** 按 providerCode 查目录条目；未命中返回 null（自定义供应商）。 */
export function findCatalogProvider(providerCode: string): CatalogProvider | null {
  return PROVIDER_CATALOG.find(p => p.providerCode === providerCode) || null
}
