<template>
    <div id="profile">
        <!-- 余额卡片 -->
        <a-card title="账户余额" :loading="balanceLoading" style="margin-bottom: 16px">
            <a-row :gutter="16">
                <a-col :span="8">
                    <a-statistic title="当前余额" :value="balanceInfo.balance || 0" prefix="¥" :precision="2"
                        :value-style="{
                            color: (balanceInfo.balance || 0) > 10 ? '#3f8600' : '#cf1322',
                            fontSize: '32px',
                            fontWeight: 'bold'
                        }" />
                    <a-button type="primary" size="large" @click="showRechargeModal"
                        style="margin-top: 16px; width: 100%">
                        立即充值
                    </a-button>
                </a-col>
                <a-col :span="8">
                    <a-statistic title="累计充值" :value="balanceInfo.totalRecharge || 0" prefix="¥" :precision="2"
                        :value-style="{ color: '#1890ff' }" />
                </a-col>
                <a-col :span="8">
                    <a-statistic title="累计消费" :value="balanceInfo.totalSpending || 0" prefix="¥" :precision="2"
                        :value-style="{ color: '#faad14' }" />
                </a-col>
            </a-row>
        </a-card>

        <!-- 配额 / Token / 费用 卡片 -->
        <a-row :gutter="16" style="margin-bottom: 16px">
            <a-col :span="8">
                <a-card title="配额信息" :loading="loading" style="height: 100%">
                    <a-statistic title="Token配额" :value="quotaInfo.tokenQuota === -1 ? '无限制' : quotaInfo.tokenQuota"
                        :value-style="{ color: '#3f8600' }" />
                    <a-divider />
                    <a-statistic title="已使用" :value="quotaInfo.usedTokens || 0" suffix="Tokens" />
                    <a-divider />
                    <a-statistic title="剩余配额" :value="quotaInfo.remainingQuota === -1 ? '无限制' : quotaInfo.remainingQuota"
                        :value-style="{ color: '#cf1322' }" />
                    <a-progress v-if="quotaInfo.tokenQuota !== -1" :percent="usagePercent"
                        :status="usagePercent > 90 ? 'exception' : 'normal'" style="margin-top: 16px" />
                </a-card>
            </a-col>
            <a-col :span="8">
                <a-card title="Token消耗" :loading="loading" style="height: 100%">
                    <a-statistic title="累计消耗" :value="summaryStats.totalTokens || 0" suffix="Tokens"
                        :value-style="{ color: '#1890ff' }" />
                    <a-divider />
                    <a-statistic title="总请求数" :value="summaryStats.totalRequests || 0" />
                    <a-divider />
                    <a-statistic title="成功请求数" :value="summaryStats.successRequests || 0"
                        :value-style="{ color: '#52c41a' }" />
                </a-card>
            </a-col>
            <a-col :span="8">
                <a-card title="费用统计" :loading="loading" style="height: 100%">
                    <a-statistic title="累计消费" :value="summaryStats.totalCost || 0" prefix="¥" :precision="2"
                        :value-style="{ color: '#faad14' }" />
                    <a-divider />
                    <a-statistic title="今日消费" :value="summaryStats.todayCost || 0" prefix="¥" :precision="2" />
                </a-card>
            </a-col>
        </a-row>

        <!-- 充值记录和消费账单 -->
        <a-row :gutter="16" style="margin-bottom: 16px">
            <a-col :span="12">
                <a-card title="充值记录" :loading="rechargeLoading">
                    <a-table :columns="rechargeColumns" :data-source="rechargeRecords"
                        :pagination="rechargePagination" @change="handleRechargeTableChange" size="small"
                        :scroll="{ x: 'max-content' }" />
                </a-card>
            </a-col>
            <a-col :span="12">
                <a-card title="消费账单" :loading="billingLoading">
                    <a-table :columns="billingColumns" :data-source="billingRecords" :pagination="billingPagination"
                        @change="handleBillingTableChange" size="small" :scroll="{ x: 'max-content' }" />
                </a-card>
            </a-col>
        </a-row>

        <!-- 每日消耗趋势图 -->
        <a-card title="每日消耗趋势（最近7天）" :loading="chartLoading">
            <div ref="chartRef" style="width: 100%; height: 400px"></div>
        </a-card>

        <!-- 充值弹窗 -->
        <a-modal v-model:open="rechargeModalVisible" title="账户充值" :footer="null" width="600px">
            <a-form layout="vertical">
                <a-form-item label="选择充值金额">
                    <a-radio-group v-model:value="selectedAmount" size="large" style="width: 100%">
                        <a-radio-button :value="10" style="width: 20%">¥10</a-radio-button>
                        <a-radio-button :value="50" style="width: 20%">¥50</a-radio-button>
                        <a-radio-button :value="100" style="width: 20%">¥100</a-radio-button>
                        <a-radio-button :value="500" style="width: 20%">¥500</a-radio-button>
                        <a-radio-button value="custom" style="width: 20%">自定义</a-radio-button>
                    </a-radio-group>
                </a-form-item>
                <a-form-item v-if="selectedAmount === 'custom'" label="自定义金额">
                    <a-input-number v-model:value="customAmount" :min="10" :max="10000" :precision="2"
                        placeholder="最低 ¥10" style="width: 100%" size="large">
                        <template #prefix>¥</template>
                    </a-input-number>
                </a-form-item>
                <a-form-item>
                    <a-button type="primary" size="large" block :loading="recharging" @click="handleRecharge">
                        <template #icon>
                            <CreditCardOutlined />
                        </template>
                        确认充值
                    </a-button>
                </a-form-item>
            </a-form>
            <a-alert message="提示" description="本项目使用 Stripe 沙箱环境，测试卡号：4242 4242 4242 4242" type="info" show-icon
                style="margin-top: 16px" />
        </a-modal>
    </div>
</template>

<script lang="ts" setup>
import { onMounted, onUnmounted, ref, computed, nextTick } from 'vue'
import { getMySummaryStats, getMyDailyStats } from '@/api/statsController'
import { getMyBalance, getMyBillingRecords } from '@/api/balanceController'
import { createStripeRecharge, getMyRechargeRecords } from '@/api/rechargeController'
import { message } from 'ant-design-vue'
import { CreditCardOutlined } from '@ant-design/icons-vue'
import * as echarts from 'echarts'

const loading = ref(false)
const chartLoading = ref(false)
const quotaInfo = ref<any>({})
const summaryStats = ref<API.UserSummaryStatsVO>({})
const dailyStats = ref<any[]>([])
const chartRef = ref()
let chartInstance: echarts.ECharts | null = null

// ------- 余额相关 -------
const balanceLoading = ref(false)
const balanceInfo = ref<API.BalanceVO>({})

const loadBalance = async () => {
    balanceLoading.value = true
    try {
        const res = await getMyBalance()
        if (res.data.code === 200 && res.data.data) {
            balanceInfo.value = res.data.data
        } else {
            message.error('获取余额信息失败：' + res.data.msg)
        }
    } catch (error) {
        message.error('获取余额信息失败')
    } finally {
        balanceLoading.value = false
    }
}

// ------- 充值相关 -------
const rechargeModalVisible = ref(false)
const selectedAmount = ref<number | 'custom'>(10)
const customAmount = ref<number>()
const recharging = ref(false)

const showRechargeModal = () => {
    rechargeModalVisible.value = true
}

// 处理充值
const handleRecharge = async () => {
    const amount =
        selectedAmount.value === 'custom' ? customAmount.value : selectedAmount.value
    if (!amount || amount < 10) {
        message.error('单笔充值金额不能低于 ¥10')
        return
    }
    recharging.value = true
    try {
        const res = await createStripeRecharge({ amount })

        if (res.data.code === 200 && res.data.data?.checkoutUrl) {
            // 跳转到 Stripe 支付页面
            message.success('正在跳转到支付页面...')
            window.location.href = res.data.data.checkoutUrl
        } else {
            message.error('创建充值订单失败：' + res.data.msg)
        }
    } catch (error: any) {
        message.error('创建充值订单失败：' + (error.message || '未知错误'))
    } finally {
        recharging.value = false
    }
}

// ------- 充值记录 -------
const rechargeLoading = ref(false)
const rechargeRecords = ref<API.RechargeRecord[]>([])
const rechargePagination = ref({ current: 1, pageSize: 5, total: 0 })
const rechargeColumns = [
    { title: '金额', dataIndex: 'amount', key: 'amount', customRender: ({ text }: any) => `¥${(text ?? 0).toFixed(2)}` },
    { title: '支付方式', dataIndex: 'paymentMethod', key: 'paymentMethod' },
    { title: '状态', dataIndex: 'status', key: 'status' },
    { title: '时间', dataIndex: 'createTime', key: 'createTime' },
]

const loadRechargeRecords = async () => {
    rechargeLoading.value = true
    try {
        const res = await getMyRechargeRecords({
            pageNum: rechargePagination.value.current,
            pageSize: rechargePagination.value.pageSize,
        })
        if (res.data.code === 200 && res.data.data) {
            rechargeRecords.value = res.data.data.records || []
            rechargePagination.value.total = res.data.data.totalRow || 0
        } else {
            message.error('获取充值记录失败：' + res.data.msg)
        }
    } catch (error) {
        message.error('获取充值记录失败')
    } finally {
        rechargeLoading.value = false
    }
}

const handleRechargeTableChange = (pagination: any) => {
    rechargePagination.value.current = pagination.current
    rechargePagination.value.pageSize = pagination.pageSize
    loadRechargeRecords()
}

// ------- 消费账单 -------
const billingLoading = ref(false)
const billingRecords = ref<API.BillingRecord[]>([])
const billingPagination = ref({ current: 1, pageSize: 5, total: 0 })
const billingColumns = [
    { title: '金额', dataIndex: 'amount', key: 'amount', customRender: ({ text }: any) => `¥${(text ?? 0).toFixed(2)}` },
    { title: '说明', dataIndex: 'description', key: 'description' },
    { title: '类型', dataIndex: 'billingType', key: 'billingType' },
    { title: '时间', dataIndex: 'createTime', key: 'createTime' },
]

const loadBillingRecords = async () => {
    billingLoading.value = true
    try {
        const res = await getMyBillingRecords({
            pageNum: billingPagination.value.current,
            pageSize: billingPagination.value.pageSize,
        })
        if (res.data.code === 200 && res.data.data) {
            billingRecords.value = res.data.data.records || []
            billingPagination.value.total = res.data.data.totalRow || 0
        } else {
            message.error('获取消费账单失败：' + res.data.msg)
        }
    } catch (error) {
        message.error('获取消费账单失败')
    } finally {
        billingLoading.value = false
    }
}

const handleBillingTableChange = (pagination: any) => {
    billingPagination.value.current = pagination.current
    billingPagination.value.pageSize = pagination.pageSize
    loadBillingRecords()
}

// 计算配额使用百分比
const usagePercent = computed(() => {
    if (
        !quotaInfo.value.tokenQuota ||
        quotaInfo.value.tokenQuota === -1 ||
        !quotaInfo.value.usedTokens
    ) {
        return 0
    }
    return Math.round((quotaInfo.value.usedTokens / quotaInfo.value.tokenQuota) * 100)
})

// 加载综合统计数据
const loadSummaryStats = async () => {
    loading.value = true
    try {
        const res = await getMySummaryStats()
        if (res.data.code === 200 && res.data.data) {
            summaryStats.value = res.data.data
            quotaInfo.value = {
                tokenQuota: res.data.data.tokenQuota,
                usedTokens: res.data.data.usedTokens,
                remainingQuota: res.data.data.remainingQuota,
            }
        } else {
            message.error('获取统计数据失败：' + res.data.msg)
        }
    } catch (error) {
        message.error('获取统计数据失败')
    } finally {
        loading.value = false
    }
}

// 加载每日统计数据
const loadDailyStats = async () => {
    chartLoading.value = true
    try {
        const endDate = new Date()
        const startDate = new Date()
        startDate.setDate(startDate.getDate() - 6)

        const res = await getMyDailyStats({
            startDate: startDate.toISOString().split('T')[0],
            endDate: endDate.toISOString().split('T')[0],
        })

        if (res.data.code === 200 && res.data.data) {
            dailyStats.value = res.data.data
            chartLoading.value = false
            await nextTick()
            renderChart()
        } else {
            message.error('获取每日统计失败：' + res.data.msg)
            chartLoading.value = false
        }
    } catch (error) {
        message.error('获取每日统计失败')
        chartLoading.value = false
    }
}

const renderChart = async () => {
    if (!chartRef.value || dailyStats.value.length === 0) {
        return
    }

    await nextTick()

    // 如果已存在图表实例，先销毁
    if (chartInstance) {
        chartInstance.dispose()
    }

    // 初始化图表
    chartInstance = echarts.init(chartRef.value)

    // 提取并转换数据为数字类型
    const dates = dailyStats.value.map((item) => item.date)
    const tokens = dailyStats.value.map((item) => Number(item.totalTokens) || 0)
    const costs = dailyStats.value.map((item) => Number(item.totalCost) || 0)
    const requests = dailyStats.value.map((item) => Number(item.requestCount) || 0)

    const option = {
        tooltip: {
            trigger: 'axis',
            axisPointer: {
                type: 'cross',
            },
            formatter: (params: any) => {
                let result = `${params[0].axisValue}<br/>`
                params.forEach((param: any) => {
                    const value = param.seriesName === '费用（元）'
                        ? `¥${param.value.toFixed(2)}`
                        : param.value
                    result += `${param.marker}${param.seriesName}: ${value}<br/>`
                })
                return result
            },
        },
        legend: {
            data: ['Token消耗', '费用（元）', '请求次数'],
        },
        grid: {
            left: '3%',
            right: '4%',
            bottom: '3%',
            containLabel: true,
        },
        xAxis: {
            type: 'category',
            boundaryGap: false,
            data: dates,
        },
        yAxis: [
            {
                type: 'value',
                name: 'Tokens / 请求次数',
                position: 'left',
            },
            {
                type: 'value',
                name: '费用（元）',
                position: 'right',
                axisLabel: {
                    formatter: '¥{value}',
                },
            },
        ],
        series: [
            {
                name: 'Token消耗',
                type: 'line',
                data: tokens,
                smooth: true,
                itemStyle: {
                    color: '#1890ff',
                },
                lineStyle: {
                    color: '#1890ff',
                },
            },
            {
                name: '费用（元）',
                type: 'line',
                yAxisIndex: 1,
                data: costs,
                smooth: true,
                itemStyle: {
                    color: '#faad14',
                },
                lineStyle: {
                    color: '#faad14',
                },
            },
            {
                name: '请求次数',
                type: 'line',
                data: requests,
                smooth: true,
                itemStyle: {
                    color: '#52c41a',
                },
                lineStyle: {
                    color: '#52c41a',
                },
            },
        ],
    }

    chartInstance.setOption(option)
}

// 响应式调整
const handleResize = () => {
    if (chartInstance) {
        chartInstance.resize()
    }
}

// 组件挂载时加载数据并添加监听
onMounted(() => {
    loadSummaryStats()
    loadDailyStats()
    loadBalance()
    loadRechargeRecords()
    loadBillingRecords()
    window.addEventListener('resize', handleResize)
})

// 组件卸载时清理
onUnmounted(() => {
    window.removeEventListener('resize', handleResize)
    if (chartInstance) {
        chartInstance.dispose()
        chartInstance = null
    }
})
</script>