import request from './request'
import { paths } from './paths'
import type { ReviewRecord, CreateReviewRequest, LongId } from '@/types'

export const reviewApi = {
  list(subTaskId?: LongId) {
    return request.get<any, ReviewRecord[]>(paths.reviews.list, { params: { subTaskId } })
  },
  getById(id: LongId) {
    return request.get<any, ReviewRecord>(paths.reviews.getById(id))
  },
  create(data: CreateReviewRequest) {
    return request.post<any, ReviewRecord>(paths.reviews.create, data)
  }
}
