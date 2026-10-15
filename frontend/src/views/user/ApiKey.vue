<template>
    <div class="api-key-page">
        <a-card title="API Key 管理">
            <template #extra>
                <a-button type="primary" @click="showCreateModal">创建 API Key</a-button>
            </template>

            <!-- API Key 列表 -->
            <a-table :columns="columns" :data-source="apiKeys" :loading="loading" :pagination="pagination" row-key="id"
                @change="handleTableChange">
                <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'status'">
                        <a-tag :color="record.status === 'active' ? 'green' : 'red'">
                            {{ record.status === 'active' ? '有效' : '已撤销' }}
                        </a-tag>
                    </template>

                    <template v-else-if="column.key === 'action'">
                        <a-space>
                            <a-button size="small" @click="handleRotate(record)">
                                重新生成
                            </a-button>
                            <a-button size="small" danger :disabled="record.status !== 'active'"
                                @click="handleRevoke(record)">
                                撤销
                            </a-button>
                        </a-space>
                    </template>
                </template>
            </a-table>
        </a-card>
    </div>

    <a-modal v-model:open="createModalVisible" :title="newApiKey ? 'API Key' : '创建 API Key'" @ok="handleOk" @cancel="handleCloseModal"
        :confirm-loading="creating" :ok-text="newApiKey ? '完成' : '确定'">
        <a-form :model="createForm" :label-col="{ span: 6 }" v-if="!newApiKey">
            <a-form-item label="Key 名称" name="keyName">
                <a-input v-model:value="createForm.keyName" placeholder="请输入 Key 名称" />
            </a-form-item>
        </a-form>

        <!-- 新建或轮换后的 Key -->
        <a-alert v-if="newApiKey" type="success" :message="rotatedHint ? '已生成新的 API Key！' : 'API Key 创建成功！'" style="margin-top: 16px">
            <template #description>
                <div style="margin-top: 8px">
                    <strong v-if="rotatedHint">原来的 Key 已失效。请复制保存以下新 Key，关闭后将无法再次查看完整内容：</strong>
                    <strong v-else>请复制保存以下 API Key，关闭后将无法再次查看完整内容：</strong>
                    <a-input :value="newApiKey.keyValue" readonly style="margin-top: 8px">
                        <template #addonAfter>
                            <a-button size="small" @click="copyApiKey">复制</a-button>
                        </template>
                    </a-input>
                </div>
            </template>
        </a-alert>
    </a-modal>
</template>

<script lang="ts" setup>
import { createApiKey, listMyApiKeys, revokeApiKey, rotateApiKey } from '@/api/apiKeyController'
import { message, Modal } from 'ant-design-vue'
import { onMounted, ref } from 'vue'

const columns = [
    {
        title: 'Key 名称',
        dataIndex: 'keyName',
        key: 'keyName',
    },
    {
        title: 'API Key',
        dataIndex: 'keyValue',
        key: 'keyValue',
    },
    {
        title: '状态',
        key: 'status',
        dataIndex: 'status',
    },
    {
        title: '已使用Token数',
        dataIndex: 'totalTokens',
        key: 'totalTokens',
    },
    {
        title: '最后使用时间',
        dataIndex: 'lastUsedTime',
        key: 'lastUsedTime',
    },
    {
        title: '创建时间',
        dataIndex: 'createTime',
        key: 'createTime',
    },
    {
        title: '操作',
        key: 'action',
    },
]

// API Key 列表
const apiKeys = ref<API.ApiKeyVO[]>([])
// 加载状态
const loading = ref(false)
// 创建弹窗显示状态
const createModalVisible = ref(false)
// 创建中状态
const creating = ref(false)
// 创建表单数据
const createForm = ref<API.ApiKeyCreateRequest>({})
// 新创建的 Key
const newApiKey = ref<API.ApiKeyVO>()
// 当前弹窗里展示的 Key 是「轮换」来的还是「创建」来的（仅影响文案）
const rotatedHint = ref(false)
// 分页配置
const pagination = ref({
    current: 1,
    pageSize: 10,
    total: 0,
    showSizeChanger: true,
    showTotal: (total: number) => `共 ${total} 条`,
})

// 显示创建弹窗
const showCreateModal = () => {
    // 重置上一次的创建状态
    createForm.value = {}
    newApiKey.value = undefined
    rotatedHint.value = false
    createModalVisible.value = true
}

// 关闭弹窗
const handleCloseModal = () => {
    createModalVisible.value = false
    createForm.value = {}
    newApiKey.value = undefined
    rotatedHint.value = false
}

// 处理弹窗 OK 按钮：已创建则关闭，否则执行创建
const handleOk = () => {
    if (newApiKey.value) {
        handleCloseModal()
    } else {
        handleCreate()
    }
}

// 加载 API Key 列表
const loadApiKeys = async (pageNum?: number, pageSize?: number) => {
    loading.value = true
    try {
        const res = await listMyApiKeys({
            pageNum: pageNum || pagination.value.current,
            pageSize: pageSize || pagination.value.pageSize,
        })

        if (res.data.code === 200 && res.data.data) {
            apiKeys.value = res.data.data.records || []
            pagination.value.total = res.data.data.totalRow || 0
        }
    } catch (error: any) {
    } finally {
        loading.value = false
    }
}

// 处理分页变化
const handleTableChange = (pag: any) => {
    pagination.value.current = pag.current
    pagination.value.pageSize = pag.pageSize
    loadApiKeys(pag.current, pag.pageSize)
}

// 处理创建 API Key
const handleCreate = async () => {
    creating.value = true
    try {
        const res = await createApiKey(createForm.value)
        if (res.data.code === 200 && res.data.data) {
            message.success('创建成功')
            newApiKey.value = res.data.data
            // 刷新列表
            await loadApiKeys()
        } else {
            message.error(res.data.msg || '创建失败')
        }
    } catch (error: any) {
    } finally {
        creating.value = false
    }
}

// 处理撤销 API Key
const handleRevoke = (record: API.ApiKeyVO) => {
    Modal.confirm({
        title: '确认撤销',
        content: `确定要撤销 API Key "${record.keyName || record.keyValue}" 吗？撤销后将无法恢复。`,
        onOk: async () => {
            try {
                const res = await revokeApiKey({ id: record.id })
                if (res.data.code === 200) {
                    message.success('撤销成功')
                    await loadApiKeys()
                } else {
                    message.error(res.data.msg || '撤销失败')
                }
            } catch (error: any) {
                message.error('撤销失败：' + error.message)
            }
        },
    })
}

// 处理重新生成（轮换）API Key
// 明文不可逆，丢了 Key 只能换发新的；旧 Key 会被立即作废
const handleRotate = (record: API.ApiKeyVO) => {
    Modal.confirm({
        title: '确认重新生成',
        content: `将换发一把新的 API Key，原来的 Key「${record.keyValue}」会立即失效。`
            + `如果它已经用在某个地方，记得同步更新。`,
        okText: '重新生成',
        onOk: async () => {
            try {
                const res = await rotateApiKey({ id: record.id })
                if (res.data.code === 200 && res.data.data) {
                    // 复用创建时的弹窗：新明文只在这里出现一次
                    rotatedHint.value = true
                    newApiKey.value = res.data.data
                    createModalVisible.value = true
                    await loadApiKeys()
                } else {
                    message.error(res.data.msg || '重新生成失败')
                }
            } catch (e: unknown) {
                message.error('重新生成失败：' + (e instanceof Error ? e.message : String(e)))
            }
        },
    })
}

// 复制 API Key
const copyApiKey = () => {
    if (newApiKey.value?.keyValue) {
        navigator.clipboard.writeText(newApiKey.value.keyValue)
        message.success('已复制到剪贴板')
    }
}

onMounted(() => {
    loadApiKeys()
})
</script>