import request from './request'
import { paths } from './paths'
import type { Rule, LongId } from '@/types'

export const ruleApi = {
  list(params?: { ruleType?: string }) {
    return request.get<any, Rule[]>(paths.rules.list, { params })
  },
  getById(id: LongId) {
    return request.get<any, Rule>(paths.rules.getById(id))
  },
  create(data: Partial<Rule>) {
    return request.post(paths.rules.create, data)
  },
  update(id: LongId, data: Partial<Rule>) {
    return request.put(paths.rules.update(id), data)
  },
  remove(id: LongId) {
    return request.delete(paths.rules.delete(id))
  }
}
