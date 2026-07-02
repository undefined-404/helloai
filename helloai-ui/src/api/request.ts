import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { R } from '@/types'

const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = sessionStorage.getItem('adminToken')
  if (token) {
    config.headers['X-Admin-Token'] = token
  }
  const agentKey = sessionStorage.getItem('agentKey')
  if (agentKey) {
    config.headers['Authorization'] = `Bearer ${agentKey}`
  }
  return config
})

instance.interceptors.response.use(
  (response: AxiosResponse<R<any>>) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    // 登录接口错误由页面自己处理提示
    if (response.config.url?.includes('/auth/')) {
      return Promise.reject(new Error(res.msg))
    }
    if (res.code === 401 || res.code === 403) {
      ElMessage.error(res.msg || '认证失败')
      sessionStorage.removeItem('adminToken')
      sessionStorage.removeItem('agentKey')
      window.location.hash = '#/login'
      return Promise.reject(new Error(res.msg))
    }
    ElMessage.error(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg))
  },
  (error) => {
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

export default instance
