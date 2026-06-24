// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 获取我的充值记录 GET /api/recharge/list/my */
export async function getMyRechargeRecords(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getMyRechargeRecordsParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageRechargeRecord>('/api/recharge/list/my', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** Stripe充值取消回调 GET /api/recharge/stripe/cancel */
export async function stripeCancel(options?: { [key: string]: any }) {
  return request<API.ResultString>('/api/recharge/stripe/cancel', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 创建Stripe充值订单 POST /api/recharge/stripe/create */
export async function createStripeRecharge(
  body: API.CreateRechargeRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultRechargeVO>('/api/recharge/stripe/create', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** Stripe充值成功回调 GET /api/recharge/stripe/success */
export async function stripeSuccess(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.stripeSuccessParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultString>('/api/recharge/stripe/success', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}
