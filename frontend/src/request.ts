import axios from 'axios'
import { message } from 'ant-design-vue'

const myAxios = axios.create({
  baseURL: 'http://localhost:9090',
  timeout: 60000,
  withCredentials: true,
})

// 全局请求拦截器
myAxios.interceptors.request.use(
  function (config) {
    return config
  },
  function (error) {
    return Promise.reject(error)
  },
)

// 未登录处理：跳转到登录页
function handleNeedLogin(responseURL?: string) {
  if (
    !responseURL?.includes('user/get/login') &&
    !window.location.pathname.includes('/user/login')
  ) {
    message.warning('请先登录')
    window.location.href = `/user/login?redirect=${window.location.href}`
  }
}

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    return response
  },
  function (error) {
    // 后端业务异常以 HTTP 400 返回，统一在这里提示
    const data = error.response?.data
    if (data) {
      // 未登录
      if (data.code === 400001) {
        handleNeedLogin(error.response.request?.responseURL)
      } else {
        message.error(data.msg || '请求失败')
      }
    } else {
      message.error('网络异常，请稍后重试')
    }
    return Promise.reject(error)
  },
)

export default myAxios