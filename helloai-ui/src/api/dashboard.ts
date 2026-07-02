import request from './request'
import type { DashboardStats } from '@/types'

export const dashboardApi = {
  stats() {
    return request.get<any, DashboardStats>('/dashboard/stats')
  }
}