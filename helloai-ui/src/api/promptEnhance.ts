import request from './request'
import { paths } from './paths'
import type { PromptEnhanceRequest, PromptEnhanceResult } from '@/types'

// Planner Chat 输入优化（PromptEnhancer）：当前输入 → 后端走一轮低温度 LLM 结构化改写，
// 返回原文 + 优化版供预览，用户确认后自行回填；耗时与澄清单轮同量级，覆盖 120s 超时。
export const promptEnhanceApi = {
  enhance(prompt: string) {
    const body: PromptEnhanceRequest = { prompt }
    return request.post<any, PromptEnhanceResult>(
      paths.planner.promptEnhance, body, { timeout: 120_000 })
  }
}
