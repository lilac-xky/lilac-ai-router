<template>
    <div id="callHistory">
        <!-- 搜索表单 -->
        <a-form layout="inline" :model="searchParams" @finish="doSearch">
            <a-form-item label="模型">
                <a-input v-model:value="searchParams.requestModel" placeholder="输入模型标识" style="width: 200px" />
            </a-form-item>
            <a-form-item label="状态">
                <a-select v-model:value="searchParams.status" placeholder="选择状态" style="width: 120px" allow-clear>
                    <a-select-option value="success">成功</a-select-option>
                    <a-select-option value="failed">失败</a-select-option>
                </a-select>
            </a-form-item>
            <a-form-item label="开始日期">
                <a-date-picker v-model:value="startDate" placeholder="开始日期" @change="handleDateChange" />
            </a-form-item>
            <a-form-item label="结束日期">
                <a-date-picker v-model:value="endDate" placeholder="结束日期" @change="handleDateChange" />
            </a-form-item>
            <a-form-item>
                <a-button type="primary" html-type="submit">搜索</a-button>
                <a-button style="margin-left: 8px" @click="resetSearch">重置</a-button>
            </a-form-item>
        </a-form>
        <a-divider />

        <!-- 数据表格 -->
        <a-table :columns="columns" :data-source="data" :pagination="pagination" :loading="loading"
            @change="doTableChange">
            <template #bodyCell="{ column, record }">
                <template v-if="column.dataIndex === 'status'">
                    <a-tag v-if="record.status === 'success'" color="green">成功</a-tag>
                    <a-tag v-else color="red">失败</a-tag>
                </template>
                <template v-else-if="column.dataIndex === 'cost'">
                    <span>¥{{ (record.cost || 0).toFixed(6) }}</span>
                </template>
                <template v-else-if="column.dataIndex === 'duration'">
                    <span>{{ record.duration }} ms</span>
                </template>
                <template v-else-if="column.dataIndex === 'createTime'">
                    {{ dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') }}
                </template>
                <template v-else-if="column.key === 'action'">
                    <a-button type="link" @click="showDetail(record)">详情</a-button>
                </template>
            </template>
        </a-table>
        <!-- 详情弹窗 -->
        <a-modal v-model:open="detailModalVisible" title="调用详情" :footer="null" width="800px">
            <a-descriptions bordered :column="2" v-if="currentRecord">
                <a-descriptions-item label="模型" :span="2">{{ currentRecord.modelName }}</a-descriptions-item>
                <a-descriptions-item label="状态">
                    <a-tag v-if="currentRecord.status === 'success'" color="green">成功</a-tag>
                    <a-tag v-else color="red">失败</a-tag>
                </a-descriptions-item>
                <a-descriptions-item label="输入Token">{{
                    currentRecord.promptTokens
                }}</a-descriptions-item>
                <a-descriptions-item label="输出Token">{{
                    currentRecord.completionTokens
                }}</a-descriptions-item>
                <a-descriptions-item label="总Token">{{
                    currentRecord.totalTokens
                }}</a-descriptions-item>
                <a-descriptions-item label="费用">¥{{ (currentRecord.cost || 0).toFixed(6) }}</a-descriptions-item>
                <a-descriptions-item label="耗时">{{ currentRecord.duration }} ms</a-descriptions-item>
                <a-descriptions-item label="创建时间" :span="2">{{
                    dayjs(currentRecord.createTime).format('YYYY-MM-DD HH:mm:ss')
                }}</a-descriptions-item>
                <a-descriptions-item v-if="currentRecord.errorMessage" label="错误信息" :span="2">
                    <a-typography-text type="danger">{{
                        currentRecord.errorMessage
                    }}</a-typography-text>
                </a-descriptions-item>
            </a-descriptions>
        </a-modal>
    </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { pageMyHistory } from '@/api/statsController'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'

const columns = [
    {
        title: '模型',
        dataIndex: 'modelName',
        width: 160,
        ellipsis: true,
    },
    {
        title: '状态',
        dataIndex: 'status',
        width: 80,
    },
    {
        title: 'Token',
        dataIndex: 'totalTokens',
        width: 100,
    },
    {
        title: '费用',
        dataIndex: 'cost',
        width: 120,
    },
    {
        title: '耗时',
        dataIndex: 'duration',
        width: 100,
    },
    {
        title: '创建时间',
        dataIndex: 'createTime',
        width: 180,
    },
    {
        title: '操作',
        key: 'action',
        width: 80,
        fixed: 'right',
    },
]

const data = ref<API.RequestLog[]>([])
const total = ref(0)
const loading = ref(false)
const detailModalVisible = ref(false)
const currentRecord = ref<API.RequestLog>()
const startDate = ref()
const endDate = ref()

// 搜索条件
const searchParams = reactive<API.RequestLogQueryRequest>({
    pageNum: 1,
    pageSize: 10,
})

// 获取数据
const fetchData = async () => {
    loading.value = true
    try {
        const res = await pageMyHistory({ ...searchParams })
        if (res.data.code === 200 && res.data.data) {
            data.value = res.data.data.records ?? []
            total.value = res.data.data.totalRow ?? 0
        } else {
            message.error('获取数据失败：' + res.data.msg)
        }
    } catch (error) {
        message.error('获取数据失败')
    } finally {
        loading.value = false
    }
}

// 分页参数
const pagination = computed(() => {
    return {
        current: searchParams.pageNum ?? 1,
        pageSize: searchParams.pageSize ?? 10,
        total: total.value,
        showSizeChanger: true,
        showTotal: (total: number) => `共 ${total} 条`,
    }
})

// 表格分页变化时的操作
const doTableChange = (page: { current: number; pageSize: number }) => {
    searchParams.pageNum = page.current
    searchParams.pageSize = page.pageSize
    fetchData()
}

// 日期选择变化时同步到搜索条件
const handleDateChange = () => {
    searchParams.startDate = startDate.value ? dayjs(startDate.value).format('YYYY-MM-DD') : undefined
    searchParams.endDate = endDate.value ? dayjs(endDate.value).format('YYYY-MM-DD') : undefined
}

// 查看详情
const showDetail = (record: API.RequestLog) => {
    currentRecord.value = record
    detailModalVisible.value = true
}

// 搜索数据
const doSearch = () => {
    searchParams.pageNum = 1
    fetchData()
}

// 重置搜索
const resetSearch = () => {
    searchParams.requestModel = undefined
    searchParams.status = undefined
    searchParams.startDate = undefined
    searchParams.endDate = undefined
    startDate.value = undefined
    endDate.value = undefined
    searchParams.pageNum = 1
    fetchData()
}

onMounted(() => {
    fetchData()
})
</script>