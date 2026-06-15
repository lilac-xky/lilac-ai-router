// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 创建 API Key POST /api/api/key/create */
export async function createApiKey(
  body: API.ApiKeyCreateRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultApiKeyVO>('/api/api/key/create', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取我的 API Key 列表 GET /api/api/key/list/my */
export async function listMyApiKeys(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.listMyApiKeysParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageApiKeyVO>('/api/api/key/list/my', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 撤销 API Key POST /api/api/key/revoke */
export async function revokeApiKey(body: API.DeleteRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/api/key/revoke', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取 Token 消耗数 传 apiKeyId 时返回该 API Key 的消耗数；不传时返回当前用户所有 API Key 的累计消耗数 GET /api/api/key/token/stats */
export async function getMyTokenStats(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getMyTokenStatsParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultLong>('/api/api/key/token/stats', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}
