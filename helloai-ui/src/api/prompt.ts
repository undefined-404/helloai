import request from './request'
import { paths } from './paths'
import type { PromptTemplate, LongId } from '@/types'

export const promptApi = {
  list(params?: { role?: string; category?: string }) {
    return request.get<any, PromptTemplate[]>(paths.admin.prompts, { params })
  },
  getById(id: LongId) {
    return request.get<any, PromptTemplate>(paths.admin.promptById(id))
  },
  getDefault(role: string) {
    return request.get<any, PromptTemplate>(paths.admin.promptDefault, { params: { role } })
  },
  create(data: Partial<PromptTemplate>) {
    return request.post<any, PromptTemplate>(paths.admin.prompts, data)
  },
  update(id: LongId, data: Partial<PromptTemplate>) {
    return request.put<any, PromptTemplate>(paths.admin.promptUpdate(id), data)
  },
  remove(id: LongId) {
    return request.delete(paths.admin.promptDelete(id))
  },
  compose(role: string, agentContent?: string) {
    return request.post<any, { content: string }>(paths.admin.promptCompose, { role, agentContent: agentContent || '' })
  }
}
