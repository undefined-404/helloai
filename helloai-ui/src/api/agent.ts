import request from './request'
import type { Agent } from '@/types'

export const agentApi = {
  list(params?: { role?: string; status?: string }) {
    return request.get<any, Agent[]>('/agents', { params })
  },
  getById(id: number) {
    return request.get<any, Agent>(`/agents/${id}`)
  },
  register(data: { name: string; role: string; description?: string }) {
    return request.post('/agents/register', data)
  }
}
