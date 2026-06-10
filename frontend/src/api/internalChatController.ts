// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 内部接口 POST /api/internal/chat/completions */
export async function chatCompletions(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.chatCompletionsParams,
  body: API.ChatRequest,
  options?: { [key: string]: any }
) {
  return request<{
    code?: number
    msg?: string
    data?: {
      id?: string
      object?: string
      created?: number
      model?: string
      choices?: API.Choice[]
      usage?: API.Usage
    }
  }>('/api/internal/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    params: {
      ...params,
    },
    data: body,
    ...(options || {}),
  })
}
