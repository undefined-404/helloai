import request from './request'
import { paths } from './paths'

/**
 * 供应商余额快照（G-026，对应后端 BalanceSnapshot record）。
 * 金额型（deepseek/moonshot/bocha）读 amount；额度型（MiniMax）读 quotaPercent 且 amount 为 null。
 * source 三态：LIVE=本次实时抓到 / CACHED=命中缓存 / FAILED=抓取失败（数值字段为 null，前端显示「—」）。
 */
export interface QuotaSnapshot {
  providerKey: string
  available: boolean
  /** 金额型供应商的余额（CNY）；额度型为 null */
  amount: string | number | null
  currency?: string | null
  /** 额度型供应商的单位描述（如「百分比」） */
  rawUnit?: string | null
  /** 额度型供应商的剩余百分比（0~100）；金额型为 null */
  quotaPercent?: string | number | null
  /** 抓取/缓存时刻（epoch ms） */
  fetchedAt: string | number
  error?: string | null
  source: 'LIVE' | 'CACHED' | 'FAILED'
}

/** 供应商展示名（与后端 provider 键一致；通义不出现）。 */
export const QUOTA_PROVIDER_LABELS: Record<string, string> = {
  deepseek: 'DeepSeek',
  moonshot: 'Moonshot',
  minimax: 'MiniMax',
  bocha: '博查'
}

/** 浮球运行期配置（对应后端 GET /api/admin/quota/widget-config）。 */
export interface QuotaWidgetConfig {
  /** false：不渲染浮球也不发请求 */
  enabled: boolean
  /** 0=仅手动刷新；60~3600=自动刷新间隔（秒） */
  refreshIntervalSeconds: number
}

export const quotaApi = {
  /** 聚合 4 家余额快照（读后端缓存，缓存未命中才实时抓）。 */
  overview() {
    return request.get<any, QuotaSnapshot[]>(paths.admin.quotaOverview)
  },
  /** 只读最近一次落库快照，不触网（「仅手动刷新」模式打开面板时用）。 */
  overviewCached() {
    return request.get<any, QuotaSnapshot[]>(paths.admin.quotaOverview, { params: { fresh: false } })
  },
  /** 手动实时刷新单家（跳过缓存）。 */
  refresh(key: string) {
    return request.post<any, QuotaSnapshot>(paths.admin.quotaRefresh(key))
  },
  /** 浮球开关 + 自动刷新间隔（quota:view 保护）。 */
  widgetConfig() {
    return request.get<any, QuotaWidgetConfig>(paths.admin.quotaWidgetConfig)
  }
}