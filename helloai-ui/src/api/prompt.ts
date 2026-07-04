import request from './request'
import type { PromptTemplate } from '@/types'

export const promptApi = {
  list(params?: { role?: string; category?: string }) {
    return request.get<any, PromptTemplate[]>('/admin/prompts', { params })
  },
  getById(id: number) {
    return request.get<any, PromptTemplate>(`/admin/prompts/${id}`)
  },
  getDefault(role: string) {
    return request.get<any, PromptTemplate>('/admin/prompts/default', { params: { role } })
  },
  create(data: Partial<PromptTemplate>) {
    return request.post<any, PromptTemplate>('/admin/prompts', data)
  },
  update(id: number, data: Partial<PromptTemplate>) {
    return request.put<any, PromptTemplate>(`/admin/prompts/${id}`, data)
  },
  remove(id: number) {
    return request.delete(`/admin/prompts/${id}`)
  },
  compose(role: string, agentContent?: string) {
    return request.post<any, { content: string }>('/admin/prompts/compose', { role, agentContent: agentContent || '' })
  }
}
