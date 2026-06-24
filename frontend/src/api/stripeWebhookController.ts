// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** Stripe Webhook 回调 Stripe会在支付状态变更时调用这个接口 POST /api/webhook/stripe */
export async function handleStripeWebhook(body: string, options?: { [key: string]: any }) {
  return request<string>('/api/webhook/stripe', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
