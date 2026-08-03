// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 生成图片（OpenAI 兼容接口） POST /api/v1/images/generations */
export async function generateImage(
  body: API.ImageGenerationRequest,
  options?: { [key: string]: any }
) {
  return request<API.ImageGenerationResponse>('/api/v1/images/generations', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取我的图片生成记录 GET /api/v1/images/my/records */
export async function getMyRecords(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getMyRecordsParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageImageGenerationRecord>('/api/v1/images/my/records', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}
