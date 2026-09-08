// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 添加用户（仅管理员） POST /api/user/add */
export async function addUser(body: API.UserAddRequest, options?: { [key: string]: any }) {
  return request<API.ResultLong>('/api/user/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取用户使用分析数据（仅管理员） GET /api/user/analysis */
export async function getUserAnalysis(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getUserAnalysisParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultUserAnalysisVO>('/api/user/analysis', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 删除用户（仅管理员） POST /api/user/delete */
export async function deleteUser(body: API.DeleteRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/user/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 禁用用户（仅管理员） POST /api/user/disable */
export async function disableUser(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.disableUserParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/user/disable', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 启用用户（仅管理员） POST /api/user/enable */
export async function enableUser(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.enableUserParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/user/enable', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取当前登录用户 GET /api/user/get/login */
export async function getLoginUser(options?: { [key: string]: any }) {
  return request<API.ResultLoginUserVO>('/api/user/get/login', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 分页查询用户列表（仅管理员） POST /api/user/list/page */
export async function listUserVoByPage(
  body: API.UserQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageUserVO>('/api/user/list/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 用户登录 POST /api/user/login */
export async function userLogin(body: API.UserLoginRequest, options?: { [key: string]: any }) {
  return request<API.ResultLoginUserVO>('/api/user/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 用户注销 POST /api/user/logout */
export async function userLogout(options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/user/logout', {
    method: 'POST',
    ...(options || {}),
  })
}

/** 重置用户已使用配额 POST /api/user/quota/reset */
export async function resetUserQuota(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.resetUserQuotaParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/user/quota/reset', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 设置用户配额 POST /api/user/quota/set */
export async function setUserQuota(body: API.QuotaUpdateRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/user/quota/set', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 用户注册 POST /api/user/register */
export async function userRegister(
  body: API.UserRegisterRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultLong>('/api/user/register', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 更新用户（仅管理员） POST /api/user/update */
export async function updateUser(body: API.UserUpdateRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/user/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
