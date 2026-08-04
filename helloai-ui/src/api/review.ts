import request from './request'
import type { ReviewRecord, CreateReviewRequest } from '@/types'

export const reviewApi = {
  list(subTaskId?: number) {
    return request.get<any, ReviewRecord[]>('/reviews', { params: { subTaskId } })
  },
  getById(id: number) {
    return request.get<any, ReviewRecord>(`/reviews/getById/${id}`)
  },
  create(data: CreateReviewRequest) {
    return request.post<any, ReviewRecord>('/reviews', data)
  }
}
