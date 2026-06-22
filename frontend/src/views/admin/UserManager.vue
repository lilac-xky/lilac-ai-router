<template>
    <div id="userManagePage">
        <h2>用户管理</h2>

        <!-- 搜索表单 -->
        <a-form layout="inline" :model="searchParams" @finish="doSearch">
            <a-form-item label="账号">
                <a-input v-model:value="searchParams.userAccount" placeholder="输入账号" allow-clear />
            </a-form-item>
            <a-form-item label="昵称">
                <a-input v-model:value="searchParams.userName" placeholder="输入昵称" allow-clear />
            </a-form-item>
            <a-form-item>
                <a-button type="primary" html-type="submit">搜索</a-button>
                <a-button style="margin-left: 10px" @click="resetSearch">重置</a-button>
            </a-form-item>
        </a-form>

        <a-divider />

        <a-button type="primary" @click="showAddModal" style="margin-bottom: 16px">
            添加用户
        </a-button>

        <a-table :columns="columns" :data-source="data" :pagination="pagination" :loading="loading"
            @change="doTableChange" row-key="id" :scroll="{ x: 1300 }">
            <template #bodyCell="{ column, record }">
                <template v-if="column.dataIndex === 'userAvatar'">
                    <a-avatar :src="record.userAvatar" />
                </template>
                <template v-else-if="column.dataIndex === 'userRole'">
                    <a-tag v-if="record.userRole === 'admin'" color="gold">管理员</a-tag>
                    <a-tag v-else color="blue">普通用户</a-tag>
                </template>
                <template v-else-if="column.dataIndex === 'userStatus'">
                    <a-tag v-if="record.userStatus === 'disabled'" color="error">已禁用</a-tag>
                    <a-tag v-else color="success">正常</a-tag>
                </template>
                <template v-else-if="column.dataIndex === 'tokenQuota'">
                    {{ record.tokenQuota === -1 ? '无限制' : (record.tokenQuota ?? 0) }}
                </template>
                <template v-else-if="column.dataIndex === 'createTime'">
                    {{ record.createTime ? dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') : '-' }}
                </template>
                <template v-else-if="column.key === 'action'">
                    <a-space>
                        <a-button type="link" size="small" @click="showAnalysis(record)">分析</a-button>
                        <a-button type="link" size="small" @click="showEditModal(record)">编辑</a-button>
                        <a-button type="link" size="small" @click="showQuotaModal(record)">配额</a-button>
                        <a-button v-if="record.userStatus !== 'disabled'" type="link" size="small" danger
                            @click="doDisable(record.id)">禁用</a-button>
                        <a-button v-else type="link" size="small" @click="doEnable(record.id)">启用</a-button>
                        <a-button type="link" size="small" danger @click="doDelete(record.id)">删除</a-button>
                    </a-space>
                </template>
            </template>
        </a-table>

        <!-- 添加/编辑用户弹窗 -->
        <a-modal v-model:open="modalVisible" :title="isEdit ? '编辑用户' : '添加用户'" @ok="handleSubmit"
            @cancel="modalVisible = false" :confirm-loading="submitting" width="520px">
            <a-form :model="formData" :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
                <a-form-item label="账号" required>
                    <a-input v-model:value="formData.userAccount" placeholder="请输入账号" :disabled="isEdit" />
                </a-form-item>
                <a-form-item label="昵称">
                    <a-input v-model:value="formData.userName" placeholder="请输入昵称" />
                </a-form-item>
                <a-form-item label="头像">
                    <a-input v-model:value="formData.userAvatar" placeholder="头像地址" />
                </a-form-item>
                <a-form-item label="简介">
                    <a-textarea v-model:value="formData.userProfile" placeholder="用户简介" :rows="3" />
                </a-form-item>
                <a-form-item label="角色" v-if="isEdit">
                    <a-select v-model:value="formData.userRole" placeholder="选择角色">
                        <a-select-option value="user">普通用户</a-select-option>
                        <a-select-option value="admin">管理员</a-select-option>
                    </a-select>
                </a-form-item>
                <a-alert v-if="!isEdit" type="info" show-icon message="新增用户的默认密码为 12345678，请提醒用户及时修改" />
            </a-form>
        </a-modal>

        <!-- 配额管理弹窗 -->
        <a-modal v-model:open="quotaModalVisible" title="配额管理" :footer="null" width="480px">
            <a-form :label-col="{ span: 6 }" :wrapper-col="{ span: 16 }">
                <a-form-item label="用户">
                    {{ currentUser?.userName }}（{{ currentUser?.userAccount }}）
                </a-form-item>
                <a-form-item label="已使用Token">
                    {{ currentUser?.usedTokens ?? 0 }}
                </a-form-item>
                <a-form-item label="Token配额">
                    <a-input-number v-model:value="quotaForm.tokenQuota" :min="-1" style="width: 100%"
                        placeholder="-1 表示无限制" />
                </a-form-item>
                <a-form-item :wrapper-col="{ offset: 6, span: 16 }">
                    <a-space>
                        <a-button type="primary" @click="handleQuotaSubmit">保存配额</a-button>
                        <a-button danger @click="handleResetQuota">重置已用</a-button>
                    </a-space>
                </a-form-item>
            </a-form>
        </a-modal>

        <!-- 使用分析弹窗 -->
        <a-modal v-model:open="analysisVisible" title="使用分析" :footer="null" width="640px">
            <a-descriptions bordered :column="2" v-if="analysisData">
                <a-descriptions-item label="账号">{{ analysisData.userAccount }}</a-descriptions-item>
                <a-descriptions-item label="昵称">{{ analysisData.userName }}</a-descriptions-item>
                <a-descriptions-item label="Token配额">
                    {{ analysisData.tokenQuota === -1 ? '无限制' : analysisData.tokenQuota }}
                </a-descriptions-item>
                <a-descriptions-item label="已使用Token">{{ analysisData.usedTokens }}</a-descriptions-item>
                <a-descriptions-item label="剩余配额">
                    {{ analysisData.remainingQuota === -1 ? '无限制' : analysisData.remainingQuota }}
                </a-descriptions-item>
                <a-descriptions-item label="累计Token">{{ analysisData.totalTokens }}</a-descriptions-item>
                <a-descriptions-item label="总请求数">{{ analysisData.totalRequests }}</a-descriptions-item>
                <a-descriptions-item label="成功请求数">{{ analysisData.successRequests }}</a-descriptions-item>
                <a-descriptions-item label="累计消费">¥{{ (analysisData.totalCost || 0).toFixed(4) }}</a-descriptions-item>
                <a-descriptions-item label="今日消费">¥{{ (analysisData.todayCost || 0).toFixed(4) }}</a-descriptions-item>
            </a-descriptions>
            <a-empty v-else description="暂无数据" />
        </a-modal>
    </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { listUserVoByPage, addUser, updateUser, deleteUser } from '@/api/userController'
import {
    disableUser,
    enableUser,
    setUserQuota,
    resetUserQuota,
    getUserAnalysis,
} from '@/api/statsController'
import dayjs from 'dayjs'

// 表格列定义
const columns = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '头像', dataIndex: 'userAvatar', width: 70 },
    { title: '账号', dataIndex: 'userAccount', width: 140 },
    { title: '昵称', dataIndex: 'userName', width: 120 },
    { title: '角色', dataIndex: 'userRole', width: 100 },
    { title: '状态', dataIndex: 'userStatus', width: 90 },
    { title: 'Token配额', dataIndex: 'tokenQuota', width: 120 },
    { title: '已使用', dataIndex: 'usedTokens', width: 110 },
    { title: '创建时间', dataIndex: 'createTime', width: 180 },
    { title: '操作', key: 'action', width: 280, fixed: 'right' },
]

const data = ref<API.UserVO[]>([])
const loading = ref(false)

// 搜索参数
const searchParams = reactive<API.UserQueryRequest>({
    current: 1,
    pageSize: 10,
    userAccount: '',
    userName: '',
})

// 分页配置
const pagination = ref({
    current: 1,
    pageSize: 10,
    total: 0,
    showSizeChanger: true,
    showTotal: (total: number) => `共 ${total} 条`,
})

// 加载用户列表
const fetchData = async () => {
    loading.value = true
    try {
        const res = await listUserVoByPage(searchParams)
        if (res.data.code === 200 && res.data.data) {
            data.value = res.data.data.records || []
            pagination.value.total = res.data.data.totalRow || 0
            pagination.value.current = searchParams.current || 1
            pagination.value.pageSize = searchParams.pageSize || 10
        } else {
            message.error(res.data.msg || '加载失败')
        }
    } catch (error) {
        message.error('加载失败')
    } finally {
        loading.value = false
    }
}

// 搜索
const doSearch = () => {
    searchParams.current = 1
    fetchData()
}

// 重置搜索
const resetSearch = () => {
    searchParams.userAccount = ''
    searchParams.userName = ''
    searchParams.current = 1
    fetchData()
}

// 表格分页变化
const doTableChange = (pag: { current: number; pageSize: number }) => {
    searchParams.current = pag.current
    searchParams.pageSize = pag.pageSize
    fetchData()
}

// ==================== 添加/编辑 ====================
const modalVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)

const createDefaultFormData = (): API.UserAddRequest & API.UserUpdateRequest => ({
    id: undefined,
    userAccount: '',
    userName: '',
    userAvatar: '',
    userProfile: '',
    userRole: 'user',
})

const formData = reactive(createDefaultFormData())

const showAddModal = () => {
    isEdit.value = false
    Object.assign(formData, createDefaultFormData())
    modalVisible.value = true
}

const showEditModal = (record: API.UserVO) => {
    isEdit.value = true
    Object.assign(formData, {
        id: record.id,
        userAccount: record.userAccount,
        userName: record.userName,
        userAvatar: record.userAvatar,
        userProfile: record.userProfile,
        userRole: record.userRole || 'user',
    })
    modalVisible.value = true
}

const handleSubmit = async () => {
    if (!isEdit.value && !formData.userAccount) {
        message.warning('请填写账号')
        return
    }
    submitting.value = true
    try {
        const res = isEdit.value ? await updateUser(formData) : await addUser(formData)
        if (res.data.code === 200) {
            message.success(isEdit.value ? '更新成功' : '添加成功')
            modalVisible.value = false
            fetchData()
        } else {
            message.error(res.data.msg || '操作失败')
        }
    } catch (error) {
        message.error('操作失败')
    } finally {
        submitting.value = false
    }
}

// ==================== 删除 ====================
const doDelete = (userId?: number) => {
    if (!userId) {
        return
    }
    Modal.confirm({
        title: '确认删除',
        content: '确定要删除该用户吗？此操作不可恢复。',
        okType: 'danger',
        onOk: async () => {
            const res = await deleteUser({ id: userId })
            if (res.data.code === 200) {
                message.success('删除成功')
                fetchData()
            } else {
                message.error('删除失败：' + res.data.msg)
            }
        },
    })
}

// ==================== 禁用 / 启用 ====================
const doDisable = (userId?: number) => {
    if (!userId) {
        return
    }
    Modal.confirm({
        title: '确认禁用',
        content: '确定要禁用该用户吗？禁用后用户将无法登录和使用服务。',
        okText: '确认',
        cancelText: '取消',
        onOk: async () => {
            const res = await disableUser({ userId })
            if (res.data.code === 200) {
                message.success('禁用成功')
                fetchData()
            } else {
                message.error('禁用失败：' + res.data.msg)
            }
        },
    })
}

const doEnable = async (userId?: number) => {
    if (!userId) {
        return
    }
    const res = await enableUser({ userId })
    if (res.data.code === 200) {
        message.success('启用成功')
        fetchData()
    } else {
        message.error('启用失败：' + res.data.msg)
    }
}

// ==================== 配额管理 ====================
const quotaModalVisible = ref(false)
const currentUser = ref<API.UserVO>()
const quotaForm = reactive({
    tokenQuota: 0,
})

const showQuotaModal = (record: API.UserVO) => {
    currentUser.value = record
    quotaForm.tokenQuota = record.tokenQuota ?? 0
    quotaModalVisible.value = true
}

const handleQuotaSubmit = async () => {
    if (!currentUser.value?.id) {
        return
    }
    const res = await setUserQuota({
        userId: currentUser.value.id,
        tokenQuota: quotaForm.tokenQuota,
    })
    if (res.data.code === 200) {
        message.success('配额设置成功')
        quotaModalVisible.value = false
        fetchData()
    } else {
        message.error('配额设置失败：' + res.data.msg)
    }
}

const handleResetQuota = async () => {
    if (!currentUser.value?.id) {
        return
    }
    Modal.confirm({
        title: '确认重置',
        content: '确定要重置该用户的已使用配额吗？',
        okText: '确认',
        cancelText: '取消',
        onOk: async () => {
            const res = await resetUserQuota({ userId: currentUser.value!.id! })
            if (res.data.code === 200) {
                message.success('重置成功')
                quotaModalVisible.value = false
                fetchData()
            } else {
                message.error('重置失败：' + res.data.msg)
            }
        },
    })
}

// ==================== 使用分析 ====================
const analysisVisible = ref(false)
const analysisData = ref<API.UserAnalysisVO>()

const showAnalysis = async (record: API.UserVO) => {
    if (!record.id) {
        return
    }
    analysisData.value = undefined
    analysisVisible.value = true
    const res = await getUserAnalysis({ userId: record.id })
    if (res.data.code === 200 && res.data.data) {
        analysisData.value = res.data.data
    } else {
        message.error('获取分析数据失败：' + res.data.msg)
    }
}

onMounted(() => {
    fetchData()
})
</script>

<style scoped>
#userManagePage {
    padding: 16px;
}
</style>
