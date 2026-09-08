// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 添加用户提供者密钥 POST /api/byok/add */
export async function addUserProviderKey(
  body: API.UserProviderKeyAddRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/byok/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 删除用户提供者密钥 POST /api/byok/delete */
export async function deleteUserProviderKey(
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/byok/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取我的提供者密钥列表 GET /api/byok/my/list */
export async function listMyProviderKeys(options?: { [key: string]: any }) {
  return request<API.ResultListUserProviderKeyVO>('/api/byok/my/list', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 更新用户提供者密钥 POST /api/byok/update */
export async function updateUserProviderKey(
  body: API.UserProviderKeyUpdateRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/byok/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
