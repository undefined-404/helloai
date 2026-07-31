import request from './request'
import type { AxiosResponse } from 'axios'
import type { Attachment, LongId } from '@/types'

export const attachmentApi = {
  list(subTaskId?: LongId) {
    return request.get<any, Attachment[]>('/attachments', { params: { subTaskId } })
  },
  getById(id: LongId) {
    return request.get<any, Attachment>(`/attachments/${id}`)
  },
  // 下载附件：local:// 物化产物由后端流式返回；blob 响应在拦截器放行，
  // 返回完整 response 供 saveBlobResponse 解析文件名
  download(id: LongId) {
    return request.get<any, AxiosResponse<Blob>>(`/attachments/${id}/download`, { responseType: 'blob' })
  }
}
