// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 获取我的消费账单 GET /api/balance/billing/my */
export async function getMyBillingRecords(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getMyBillingRecordsParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageBillingRecord>('/api/balance/billing/my', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取我的余额信息 GET /api/balance/my */
export async function getMyBalance(options?: { [key: string]: any }) {
  return request<API.ResultBalanceVO>('/api/balance/my', {
    method: 'GET',
    ...(options || {}),
  })
}
