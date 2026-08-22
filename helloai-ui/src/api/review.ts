import request from './request'
import { paths } from './paths'
import type { ReviewRecord, CreateReviewRequest } from '@/types'

export const reviewApi = {
  list(subTaskId?: number) {
    return request.get<any, ReviewRecord[]>(paths.reviews.list, { params: { subTaskId } })
  },
  getById(id: number) {
    return request.get<any, ReviewRecord>(paths.reviews.getById(id))
  },
  create(data: CreateReviewRequest) {
    return request.post<any, ReviewRecord>(paths.reviews.create, data)
  }
}
