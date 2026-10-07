import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { R } from '@/types'
import { useAuthStore } from '@/stores/auth'

/**
 * 错误放行白名单：这些接口即便 code !== 200，也由调用方自行处理错误（弹错/解析），
 * 不走拦截器统一 ElMessage.error。匹配规则：URL 以任意一项开头。
 * 之前用 url.includes('/auth/') 太宽，未来业务路径里只要含 "/auth/" 就会被绕过 toast，
 * 改用精确路径前缀白名单。
 */
const ERROR_BY_CALLER_URL_PREFIXES = [
  '/auth/login',
  '/auth/logout',
  '/auth/changePassword',
  '/auth/me'
]

function shouldLetCallerHandleError(url: string | undefined): boolean {
  if (!url) return false
  return ERROR_BY_CALLER_URL_PREFIXES.some((prefix) => url.startsWith(prefix))
}

/**
 * PageResult 数值字段兜底（防御性保留）。
 *
 * 历史背景：后端全局 LongId-to-string 序列化器按「Java 类型」一刀切，把 PageResult 的
 * total / pages / current 也写成了 JSON 字符串，导致 Element Plus <el-pagination :total>
 * 校验失败、组件 DOM 不挂载、整片分页按钮消失。
 *
 * 该问题已从后端根除：JacksonConfig 收窄为「按 ID 语义判定」——只有 ID 字段写字符串，
 * 非 ID 的 Long 数值（分页计数、仪表盘计数、耗时、字节数等）写 JSON 数字，
 * 契约由 JacksonConfigLongSerializationTest 守护。
 *
 * 此处仍保留一层防御性 Number 归一化：前端与后端可独立发布，滚动升级期间可能出现
 * 「旧后端 + 新前端」的窗口，兜底可保证分页组件不因契约错位而整体消失。
 * 仅识别有 list 数组 + total 字段的对象结构（即 PageResult 形状），原地转 Number，
 * 避免每个调用方各自打补丁。
 */
function normalizePageResult(data: any): any {
  if (
    data
    && typeof data === 'object'
    && !Array.isArray(data)
    && Array.isArray((data as any).list)
    && 'total' in (data as any)
  ) {
    // 字段清单与后端 PageResult（list / total / pages / current）保持一致
    if ((data as any).total != null) (data as any).total = Number((data as any).total) || 0
    if ((data as any).pages != null) (data as any).pages = Number((data as any).pages) || 1
    if ((data as any).current != null) (data as any).current = Number((data as any).current) || 1
  }
  return data
}

/**
 * 集中管理的超时档位：
 * - fast（默认 30s）：列表、详情、状态修改等普通 CRUD
 * - llm（120s）：LLM 拆解、模型目录加载、provider 校验等依赖上游 LLM/网络的接口
 * - longReport（240s）：最终整合报告生成等 LLM 长链路 + 多轮重试接口
 *
 * 业务侧按需把 timeout 作为第三个参数传入 request.get/post：
 *   request.post(url, data, withTimeout('llm'))
 */
export const TIMEOUT = {
  fast: 30_000,
  llm: 120_000,
  longReport: 240_000
} as const

export function withTimeout(level: keyof typeof TIMEOUT) {
  return { timeout: TIMEOUT[level] }
}

const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // 唯一读取源：store。store 会从 sessionStorage 同步最新值，避免缓存不一致。
  const auth = useAuthStore()
  if (auth.adminToken) {
    config.headers['X-Admin-Token'] = auth.adminToken
  }
  if (auth.agentKey) {
    config.headers['Authorization'] = `Bearer ${auth.agentKey}`
  }
  return config
})

instance.interceptors.response.use(
  (response: AxiosResponse<R<any>>) => {
    // 非 JSON 通道（blob / text / arraybuffer / document / stream 等）原样放行，
    // 不做 R 包裹体解析；调用方直接读 response.data / response.headers。
    // 覆盖：download（blob）、previewById（blob）、previewTextById（text）。
    // 默认 responseType 缺失时按 'json' 走常规 R 解析路径。
    const rt = response.config.responseType
    if (rt && rt !== 'json') {
      return response as any
    }
    const res = response.data
    if (res.code === 200) {
      return normalizePageResult(res.data)
    }
    // 白名单内接口（登录/登出/改密/me）由页面自行处理错误提示
    if (shouldLetCallerHandleError(response.config.url)) {
      return Promise.reject(new Error(res.msg))
    }
    // 401 未认证/会话失效 → 清空登录态登出（§6.151 起 403 不再登出：
    // 403 是"已认证但无权限/功能未开启"，应只提示，避免质量看板门控等业务 403 误登出）
    if (res.code === 401) {
      ElMessage.error(res.msg || '认证失败')
      // 走 store 清空，避免业务代码再散落 sessionStorage.removeItem
      const auth = useAuthStore()
      auth.logout()
      return Promise.reject(new Error(res.msg))
    }
    if (res.code === 403) {
      ElMessage.error(res.msg || '无权限或功能未开启')
      return Promise.reject(new Error(res.msg))
    }
    ElMessage.error(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg))
  },
  (error) => {
    // 后端业务异常（BizException）以 HTTP 4xx/5xx 返回时，优先展示 R 包裹体里的中文 msg（如注册模型唯一性校验）
    // AbortController 取消导致的 canceled 错误不再向用户提示。
    const data = error.response?.data
    const message = error.message || ''
    const canceled = axios.isCancel(error) || message === 'canceled'
    if (!canceled) {
      ElMessage.error(data?.msg || message || '网络错误')
    }
    // HTTP 401（认证失败/会话失效）：统一清登录态并回登录页，避免残留无效 token
    // 导致后续请求循环 401、动态路由构建失败后页面落 404 的误导现象。
    if (error.response?.status === 401) {
      const auth = useAuthStore()
      auth.logout()
    }
    return Promise.reject(error)
  }
)

export default instance