import request from './request'
import type { Rule } from '@/types'

export const ruleApi = {
  list(params?: { ruleType?: string }) {
    return request.get<any, Rule[]>('/rules', { params })
  },
  getById(id: number) {
    return request.get<any, Rule>(`/rules/${id}`)
  }
}
