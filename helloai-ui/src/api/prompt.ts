import request from './request'
import type { PromptTemplate } from '@/types'

export const promptApi = {
  list(params?: { role?: string; category?: string }) {
    return request.get<any, PromptTemplate[]>('/admin/prompts', { params })
  },
  getById(id: number) {
    return request.get<any, PromptTemplate>(`/admin/prompts/getById/${id}`)
  },
  getDefault(role: string) {
    return request.get<any, PromptTemplate>('/admin/prompts/getDefaultByRole', { params: { role } })
  },
  create(data: Partial<PromptTemplate>) {
    return request.post<any, PromptTemplate>('/admin/prompts', data)
  },
  update(id: number, data: Partial<PromptTemplate>) {
    return request.put<any, PromptTemplate>(`/admin/prompts/updateById/${id}`, data)
  },
  remove(id: number) {
    return request.delete(`/admin/prompts/deleteById/${id}`)
  },
  compose(role: string, agentContent?: string) {
    return request.post<any, { content: string }>('/admin/prompts/compose', { role, agentContent: agentContent || '' })
  }
}
