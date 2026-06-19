// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 添加 IP 到黑名单 POST /api/admin/blacklist/add */
export async function addToBlacklist(body: API.BlacklistRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/admin/blacklist/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 检查 IP 是否在黑名单中 GET /api/admin/blacklist/check */
export async function checkBlacklist(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.checkBlacklistParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/admin/blacklist/check', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取黑名单数量 GET /api/admin/blacklist/count */
export async function getBlacklistCount(options?: { [key: string]: any }) {
  return request<API.ResultLong>('/api/admin/blacklist/count', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 获取黑名单列表 GET /api/admin/blacklist/list */
export async function getBlacklist(options?: { [key: string]: any }) {
  return request<API.ResultSetString>('/api/admin/blacklist/list', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 从黑名单移除 IP POST /api/admin/blacklist/remove */
export async function removeFromBlacklist(
  body: API.BlacklistRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/admin/blacklist/remove', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
