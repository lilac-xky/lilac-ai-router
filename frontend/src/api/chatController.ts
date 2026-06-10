// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 聊天接口 POST /api/v1/chat/completions */
export async function chatCompletions(body: API.ChatRequest, options?: { [key: string]: any }) {
  return request<{
    id?: string
    object?: string
    created?: number
    model?: string
    choices?: API.Choice[]
    usage?: API.Usage
  }>('/api/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
