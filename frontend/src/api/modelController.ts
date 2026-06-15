// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 添加模型 添加模型
添加模型 POST /api/model/add */
export async function addModel(body: API.ModelAddRequest, options?: { [key: string]: any }) {
  return request<API.ResultLong>('/api/model/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 删除模型 删除模型
删除模型 POST /api/model/delete */
export async function deleteModel(body: API.DeleteRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/model/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 分页查询模型列表 分页查询模型列表
分页查询模型列表 POST /api/model/list/page */
export async function listModelVoByPage(
  body: API.ModelQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageModelVO>('/api/model/list/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 更新模型 更新模型
更新模型 POST /api/model/update */
export async function updateModel(body: API.ModelUpdateRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/model/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
