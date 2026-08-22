import request from './request'
import { paths } from './paths'
import type { DashboardStats } from '@/types'

export const dashboardApi = {
  stats() {
    return request.get<any, DashboardStats>(paths.dashboard.stats)
  }
}