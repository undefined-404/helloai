import request from './request'
import type { Rule } from '@/types'

export const ruleApi = {
  list(params?: { ruleType?: string }) {
    return request.get<any, Rule[]>('/rules', { params })
  },
  getById(id: number) {
    return request.get<any, Rule>(`/rules/${id}`)
  },
  create(data: Partial<Rule>) {
    return request.post('/rules', data)
  },
  update(id: number, data: Partial<Rule>) {
    return request.put(`/rules/${id}`, data)
  },
  remove(id: number) {
    return request.delete(`/rules/${id}`)
  }
}
