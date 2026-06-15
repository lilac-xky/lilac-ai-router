// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 添加提供者 添加提供者
添加提供者 POST /api/provider/add */
export async function addProvider(body: API.ProviderAddRequest, options?: { [key: string]: any }) {
  return request<API.ResultLong>('/api/provider/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 删除提供者 删除提供者
删除提供者 POST /api/provider/delete */
export async function deleteProvider(body: API.DeleteRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/provider/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 查询所有提供者 查询所有提供者（用于下拉选择，不分页）
查询所有提供者 GET /api/provider/list */
export async function listProviderVo(options?: { [key: string]: any }) {
  return request<API.ResultListProviderVO>('/api/provider/list', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 分页查询提供者列表 分页查询提供者列表
分页查询提供者列表 POST /api/provider/list/page */
export async function listProviderVoByPage(
  body: API.ProviderQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageProviderVO>('/api/provider/list/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 更新提供者 更新提供者
更新提供者 POST /api/provider/update */
export async function updateProvider(
  body: API.ProviderUpdateRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/provider/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
