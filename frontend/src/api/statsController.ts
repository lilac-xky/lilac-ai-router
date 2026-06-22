// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 获取用户使用分析数据（仅管理员） GET /api/stats/analysis */
export async function getUserAnalysis(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getUserAnalysisParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultUserAnalysisVO>('/api/stats/analysis', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 禁用用户（仅管理员） POST /api/stats/disable */
export async function disableUser(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.disableUserParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/stats/disable', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 启用用户（仅管理员） POST /api/stats/enable */
export async function enableUser(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.enableUserParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/stats/enable', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取调用历史详情 GET /api/stats/history/detail */
export async function getHistoryDetail(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getHistoryDetailParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultRequestLog>('/api/stats/history/detail', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 分页查询我的调用历史 POST /api/stats/history/my/page */
export async function pageMyHistory(
  body: API.RequestLogQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageRequestLog>('/api/stats/history/my/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 分页查询所有调用历史（仅管理员） POST /api/stats/history/page */
export async function pageHistory(
  body: API.RequestLogQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.ResultPageRequestLog>('/api/stats/history/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 获取我的费用统计 GET /api/stats/my/cost */
export async function getMyCostStats(options?: { [key: string]: any }) {
  return request<API.ResultCostStatsVO>('/api/stats/my/cost', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 获取我的每日统计数据 GET /api/stats/my/daily */
export async function getMyDailyStats(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getMyDailyStatsParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultListMapObject>('/api/stats/my/daily', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取我的综合统计数据 GET /api/stats/my/summary */
export async function getMySummaryStats(options?: { [key: string]: any }) {
  return request<API.ResultUserSummaryStatsVO>('/api/stats/my/summary', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 重置用户已使用配额 POST /api/stats/quota/reset */
export async function resetUserQuota(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.resetUserQuotaParams,
  options?: { [key: string]: any }
) {
  return request<API.ResultBoolean>('/api/stats/quota/reset', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 设置用户配额 POST /api/stats/quota/set */
export async function setUserQuota(body: API.QuotaUpdateRequest, options?: { [key: string]: any }) {
  return request<API.ResultBoolean>('/api/stats/quota/set', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
