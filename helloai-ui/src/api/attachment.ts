import request from './request'
import type { Attachment } from '@/types'

export const attachmentApi = {
  list(subTaskId?: number) {
    return request.get<any, Attachment[]>('/attachments', { params: { subTaskId } })
  },
  getById(id: number) {
    return request.get<any, Attachment>(/attachments/)
  }
}