import request from './request'
import type { AxiosResponse } from 'axios'
import type { Attachment, LongId } from '@/types'

export const attachmentApi = {
  list(subTaskId?: LongId) {
    return request.get<any, Attachment[]>('/attachments', { params: { subTaskId } })
  },
  getById(id: LongId) {
    return request.get<any, Attachment>(`/attachments/getById/${id}`)
  },
  // 下载附件：local:// 物化产物由后端流式返回；blob 响应在拦截器放行，
  // 返回完整 response 供 saveBlobResponse 解析文件名
  download(id: LongId) {
    return request.get<any, AxiosResponse<Blob>>(`/attachments/downloadById/${id}`, { responseType: 'blob' })
  },
  // 浏览器内联预览：与 download 同走 blob 通道（响应不带 R 包裹体），
  // 调用方拿到 ResponseEntity<byte[]> 后用 URL.createObjectURL 渲染。
  // 后端 isPreviewable=false 时返回 413，由 axios 拦截器弹错并提示走下载。
  previewById(id: LongId) {
    return request.get<any, AxiosResponse<Blob>>(`/attachments/previewById/${id}`, { responseType: 'blob' })
  },
  // 文本/源码预览：文本类（md/json/xml/yaml/csv/html/txt/log/svg）走 text 通道，
  // 由前端组件内联渲染（继承主题色，避免 iframe 沙箱白底黑字）。
  // 与 previewById 共用 413 校验；超过 5 MiB 后端拒绝。
  previewTextById(id: LongId) {
    return request.get<any, AxiosResponse<string>>(`/attachments/previewById/${id}`, { responseType: 'text' })
  }
}
