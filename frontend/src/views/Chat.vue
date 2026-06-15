<template>
    <div class="chat-page">
        <a-card title="AI 对话">
            <template #extra>
                <a-button @click="clearChat">清空对话</a-button>
            </template>

            <!-- API Key 选择 + Token 统计 -->
            <a-row :gutter="16" align="middle" style="margin-bottom: 16px">
                <a-col :flex="1">
                    <a-alert v-if="!selectedApiKeyId" message="请先选择一个 API Key" type="warning" show-icon
                        style="margin-bottom: 8px" />
                    <a-select v-model:value="selectedApiKeyId" placeholder="选择 API Key" style="width: 100%"
                        @change="loadTokenStats">
                        <a-select-option v-for="key in apiKeys" :key="key.id" :value="key.id">
                            {{ key.keyName || '未命名' }} ({{ key.keyValue }})
                        </a-select-option>
                    </a-select>
                </a-col>
                <a-col>
                    <a-statistic title="当前 API Key 已消耗 Token" :value="totalTokens" />
                </a-col>
            </a-row>

            <!-- 路由策略 / 模型选择 / 深度思考 工具栏 -->
            <div class="chat-toolbar">
                <a-dropdown :trigger="['click']">
                    <a-button class="strategy-select-button">
                        <ThunderboltOutlined />
                        <span class="strategy-label">{{ currentStrategyLabel }}</span>
                        <DownOutlined class="dropdown-icon" />
                    </a-button>
                    <template #overlay>
                        <a-menu @click="handleStrategyChange">
                            <a-menu-item v-for="option in routingStrategyOptions" :key="option.value"
                                :class="{ 'strategy-active': routingStrategy === option.value }">
                                <div class="strategy-option">
                                    <div class="strategy-option-header">
                                        <span class="strategy-option-label">{{ option.label }}</span>
                                        <a-tag v-if="routingStrategy === option.value" color="blue">当前</a-tag>
                                    </div>
                                    <div class="strategy-option-desc">{{ option.description }}</div>
                                </div>
                            </a-menu-item>
                        </a-menu>
                    </template>
                </a-dropdown>

                <a-button v-if="routingStrategy === 'fixed'" class="model-select-button" @click="showModelSelector">
                    <template v-if="selectedModel">
                        <AppstoreOutlined />
                        <span class="current-model">{{ selectedModel.modelName }}</span>
                        <a-tag v-if="selectedModel.supportReasoning === 1" color="purple">思考</a-tag>
                    </template>
                    <template v-else>
                        <AppstoreOutlined />
                        <span>选择模型</span>
                    </template>
                    <DownOutlined class="dropdown-icon" />
                </a-button>

                <div v-if="routingStrategy === 'fixed' && selectedModel?.supportReasoning === 1"
                    class="reasoning-switch">
                    <BulbOutlined class="reasoning-icon" />
                    <span class="reasoning-label">深度思考</span>
                    <a-switch v-model:checked="enableReasoning" />
                </div>
            </div>

            <!-- 消息列表 -->
            <div class="message-list" ref="messageListRef">
                <a-empty v-if="messages.length === 0 && !loading" description="开始你的对话吧" />
                <div v-for="(msg, index) in messages" :key="index" :class="['message-item', msg.role]">
                    <div class="message-role">{{ getRoleLabel(msg.role || '') }}</div>
                    <div class="message-content" v-html="renderMarkdown(msg.content || '')"></div>
                </div>
                <!-- 加载中的流式消息 -->
                <div v-if="loading" class="message-item assistant">
                    <div class="message-role">AI</div>
                    <div class="message-content">
                        <a-spin v-if="!streamingContent" />
                        <span v-else v-html="renderMarkdown(streamingContent)"></span>
                    </div>
                </div>
            </div>

            <!-- 发送失败时的重试提示 -->
            <a-alert v-if="lastError" type="error" show-icon style="margin-bottom: 16px" :message="lastError">
                <template #action>
                    <a-button size="small" type="primary" :loading="loading" @click="retryLastMessage">
                        重试
                    </a-button>
                </template>
            </a-alert>

            <!-- 输入区域 -->
            <div class="input-area">
                <a-textarea v-model:value="userInput" placeholder="请输入消息，Enter 发送，Shift + Enter 换行"
                    :auto-size="{ minRows: 2, maxRows: 6 }" :disabled="loading" @keydown.enter="handleEnter" />
                <a-button type="primary" :loading="loading" :disabled="!canSend" style="margin-top: 8px; float: right"
                    @click="debouncedSend">
                    发送
                </a-button>
            </div>
        </a-card>

        <!-- 模型选择弹窗（固定模型策略） -->
        <a-modal v-model:open="modelSelectorVisible" title="选择模型" :footer="null" width="900px"
            :body-style="{ maxHeight: '70vh', overflowY: 'auto' }">
            <a-tabs v-model:activeKey="activeCategory" type="card">
                <a-tab-pane key="all" tab="全部模型">
                    <div class="model-grid">
                        <ModelCard v-for="model in allModels" :key="model.id" :model="model"
                            :active="selectedModel?.id === model.id" @select="selectModelAndClose" />
                        <a-empty v-if="allModels.length === 0" description="暂无可用模型" />
                    </div>
                </a-tab-pane>
                <a-tab-pane key="fast" tab="快速模型">
                    <div class="model-grid">
                        <ModelCard v-for="model in fastModels" :key="model.id" :model="model"
                            :active="selectedModel?.id === model.id" @select="selectModelAndClose" />
                        <a-empty v-if="fastModels.length === 0" description="暂无快速模型" />
                    </div>
                </a-tab-pane>
                <a-tab-pane key="reasoning" tab="深度思考">
                    <div class="model-grid">
                        <ModelCard v-for="model in reasoningModels" :key="model.id" :model="model"
                            :active="selectedModel?.id === model.id" @select="selectModelAndClose" />
                        <a-empty v-if="reasoningModels.length === 0" description="暂无深度思考模型" />
                    </div>
                </a-tab-pane>
            </a-tabs>
        </a-modal>
    </div>
</template>

<script lang="ts" setup>
import { getMyTokenStats, listMyApiKeys } from '@/api/apiKeyController'
import { listModelVoByPage } from '@/api/modelController'
import {
    AppstoreOutlined,
    BulbOutlined,
    DownOutlined,
    ThunderboltOutlined,
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { debounce } from 'lodash-es'
import { marked } from 'marked'
import { computed, h, nextTick, onMounted, ref, watch } from 'vue'

// 本地存储的 key
const STORAGE_KEY = 'chat-history'
// 最多持久化的消息条数，避免 localStorage 体积无限增长
const MAX_STORED_MESSAGES = 100

// 配置 marked
marked.setOptions({
    gfm: true,
    breaks: true,
    async: false,
})

// ==================== 状态 ====================
// 路由策略
type RoutingStrategy = 'auto' | 'cost_first' | 'latency_first' | 'fixed'
const routingStrategy = ref<RoutingStrategy>('auto')
const routingStrategyOptions: { value: RoutingStrategy; label: string; description: string }[] = [
    { value: 'auto', label: '自动路由', description: '综合成本、延迟、优先级智能选择' },
    { value: 'cost_first', label: '成本优先', description: '选择费用最低的模型' },
    { value: 'latency_first', label: '延迟优先', description: '选择响应最快的模型' },
    { value: 'fixed', label: '固定模型', description: '使用选定的指定模型' },
]
const currentStrategyLabel = computed(
    () => routingStrategyOptions.find((o) => o.value === routingStrategy.value)?.label || '自动路由'
)

// 固定模型策略下选中的模型
const selectedModel = ref<API.ModelVO | null>(null)
// 是否启用深度思考
const enableReasoning = ref(false)
// 模型列表（仅对话类、启用状态）
const allModels = ref<API.ModelVO[]>([])
const fastModels = computed(() => allModels.value.filter((m) => m.supportReasoning !== 1))
const reasoningModels = computed(() => allModels.value.filter((m) => m.supportReasoning === 1))
// 模型选择弹窗
const modelSelectorVisible = ref(false)
const activeCategory = ref<'all' | 'fast' | 'reasoning'>('all')

// 当前选择的 API Key
const selectedApiKeyId = ref<number>()
// API Key 列表
const apiKeys = ref<API.ApiKeyVO[]>([])
// 消息列表
const messages = ref<API.ChatMessage[]>([])
// 用户输入
const userInput = ref('')
// 加载状态
const loading = ref(false)
// 流式响应的临时内容
const streamingContent = ref('')
// Token 统计
const totalTokens = ref(0)
// 消息列表的 DOM 引用
const messageListRef = ref<HTMLElement>()
// 最近一次的错误信息（用于展示重试入口）
const lastError = ref('')

// 是否可以发送：非加载中、有输入、有 API Key，且固定模型策略下已选模型
const canSend = computed(() => {
    if (loading.value || !userInput.value.trim() || !selectedApiKeyId.value) return false
    if (routingStrategy.value === 'fixed' && !selectedModel.value) return false
    return true
})

// ==================== 渲染辅助 ====================
// 角色标签
const getRoleLabel = (role: string) => {
    switch (role) {
        case 'user':
            return '我'
        case 'assistant':
            return 'AI'
        case 'system':
            return '系统'
        default:
            return role
    }
}

// Markdown 渲染：先把转义的 \n 还原成真实换行，再用 marked 渲染
const renderMarkdown = (content: string): string => {
    if (!content) return ''
    try {
        const unescaped = content.replace(/\\n/g, '\n')
        return marked.parse(unescaped) as string
    } catch (e) {
        console.warn('Markdown 解析失败', e)
        return content
    }
}

// 简易模型卡片（内联函数式组件，避免新建文件）
const ModelCard = (props: { model: API.ModelVO; active: boolean }, { emit }: any) =>
    h(
        'div',
        {
            class: ['model-card', { active: props.active }],
            onClick: () => emit('select', props.model),
        },
        [
            h('div', { class: 'model-header' }, [
                h('span', { class: 'model-name' }, props.model.modelName),
                props.model.supportReasoning === 1
                    ? h('span', { class: 'tag-reasoning' }, '思考')
                    : null,
            ]),
            h('div', { class: 'model-info' }, [
                h('span', { class: 'model-provider' }, props.model.providerDisplayName),
                h(
                    'span',
                    { class: 'model-price' },
                    `¥${props.model.inputPrice ?? 0}/¥${props.model.outputPrice ?? 0}`
                ),
            ]),
            props.model.description
                ? h('div', { class: 'model-desc' }, props.model.description)
                : null,
        ]
    )
    ; (ModelCard as any).props = ['model', 'active']
    ; (ModelCard as any).emits = ['select']

// ==================== 交互 ====================
// 切换路由策略
const handleStrategyChange = ({ key }: { key: RoutingStrategy }) => {
    routingStrategy.value = key
    if (key === 'fixed' && allModels.value.length === 0) {
        loadModels()
    }
}

// 打开模型选择弹窗
const showModelSelector = () => {
    if (allModels.value.length === 0) loadModels()
    modelSelectorVisible.value = true
}

// 选中模型并关闭弹窗
const selectModelAndClose = (model: API.ModelVO) => {
    selectedModel.value = model
    // 切换到不支持思考的模型时，重置开关
    if (model.supportReasoning !== 1) enableReasoning.value = false
    modelSelectorVisible.value = false
}

// 清空对话
const clearChat = () => {
    messages.value = []
    streamingContent.value = ''
    lastError.value = ''
}

// Enter 发送，Shift + Enter 换行
const handleEnter = (e: KeyboardEvent) => {
    if (e.shiftKey) return
    e.preventDefault()
    debouncedSend()
}

// 带重试的 fetch：仅对网络异常（fetch reject）做指数退避重试，HTTP 错误不重试
async function fetchWithRetry(url: string, options: RequestInit, retries = 3): Promise<Response> {
    let lastErr: unknown
    for (let i = 0; i < retries; i++) {
        try {
            return await fetch(url, options)
        } catch (error) {
            lastErr = error
            if (i < retries - 1) {
                await new Promise((resolve) => setTimeout(resolve, 1000 * (i + 1)))
            }
        }
    }
    throw lastErr
}

// 发送消息
async function handleSend() {
    if (!canSend.value) return

    const content = userInput.value.trim()
    // 先清空输入框，避免重复发送
    userInput.value = ''
    lastError.value = ''

    messages.value.push({ role: 'user', content })
    await sendCompletion()
}

// 创建防抖函数
const debouncedSend = debounce(handleSend, 300)

// 真正发起对话请求（独立出来，便于重试）
async function sendCompletion() {
    if (!selectedApiKeyId.value) return

    loading.value = true
    streamingContent.value = ''
    lastError.value = ''

    await nextTick()
    scrollToBottom()

    try {
        const chatRequest: API.ChatRequest = {
            messages: messages.value.map((m) => ({ role: m.role, content: m.content })),
            stream: true,
            routing_strategy: routingStrategy.value,
        }
        // 固定模型策略：携带模型标识与深度思考配置
        if (routingStrategy.value === 'fixed' && selectedModel.value) {
            chatRequest.model = selectedModel.value.modelKey
            if (selectedModel.value.supportReasoning === 1) {
                chatRequest.enable_reasoning = enableReasoning.value
            }
        }

        const response = await fetchWithRetry(
            `/api/internal/chat/completions?apiKeyId=${selectedApiKeyId.value}`,
            {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify(chatRequest),
            }
        )

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`)
        }

        const reader = response.body?.getReader()
        const decoder = new TextDecoder()
        if (!reader) {
            throw new Error('无法获取响应流')
        }

        let buffer = ''
        let assistantContent = ''

        // 解析一段 SSE 文本块，累加到 assistantContent
        const consume = (chunk: string) => {
            const lines = chunk.split('\n')
            for (const line of lines) {
                if (line.startsWith('data:')) {
                    const data = line.substring(5).trim()
                    if (data && data !== '[DONE]') {
                        assistantContent += data
                        streamingContent.value = assistantContent
                        scrollToBottom()
                    }
                }
            }
        }

        while (true) {
            const { done, value } = await reader.read()
            if (done) break

            buffer += decoder.decode(value, { stream: true })
            const parts = buffer.split('\n\n')
            buffer = parts.pop() || ''
            for (const part of parts) {
                consume(part)
            }
        }
        // 处理缓冲区剩余数据
        if (buffer) consume(buffer)

        // 完成
        messages.value.push({ role: 'assistant', content: assistantContent })
        streamingContent.value = ''
        await loadTokenStats()
    } catch (error: any) {
        // 保留刚才的用户消息，展示重试入口
        const msg = error?.message || '未知错误'
        lastError.value = '对话失败：' + msg
        message.error(lastError.value)
        streamingContent.value = ''
    } finally {
        loading.value = false
    }
}

// 重试最近一次对话（最后一条用户消息已在列表中，直接重新发起请求）
const retryLastMessage = () => {
    if (loading.value || messages.value.length === 0) return
    sendCompletion()
}

// 滚动到底部
const scrollToBottom = () => {
    if (messageListRef.value) {
        messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
}

// ==================== 数据加载 ====================
// 加载 API Key 列表
const loadApiKeys = async () => {
    try {
        const res = await listMyApiKeys({ pageNum: 1, pageSize: 100 })
        if (res.data.code === 200 && res.data.data) {
            const records = res.data.data.records || []
            apiKeys.value = records.filter((key) => key.status === 'active')
            if (!selectedApiKeyId.value && apiKeys.value.length > 0) {
                selectedApiKeyId.value = apiKeys.value[0]?.id
                await loadTokenStats()
            }
        }
    } catch (error: any) {
        message.error('加载 API Key 列表失败：' + error.message)
    }
}

// 加载可用模型列表（对话类、启用状态）
const loadModels = async () => {
    try {
        const res = await listModelVoByPage({
            current: 1,
            pageSize: 100,
            modelType: 'chat',
            status: 'active',
        })
        if (res.data.code === 200 && res.data.data) {
            allModels.value = res.data.data.records || []
        } else {
            message.error(res.data.msg || '加载模型列表失败')
        }
    } catch (error: any) {
        message.error('加载模型列表失败：' + error.message)
    }
}

// 加载 Token 统计
const loadTokenStats = async () => {
    if (!selectedApiKeyId.value) {
        totalTokens.value = 0
        return
    }
    try {
        const res = await getMyTokenStats({ apiKeyId: selectedApiKeyId.value })
        if (res.data.code === 200) {
            totalTokens.value = res.data.data || 0
        }
    } catch (error: any) {
        message.error('加载统计数据失败：' + error.message)
    }
}

// ==================== 持久化 ====================
watch(
    messages,
    (newMessages) => {
        try {
            const toStore = newMessages.slice(-MAX_STORED_MESSAGES)
            localStorage.setItem(STORAGE_KEY, JSON.stringify(toStore))
        } catch (e) {
            console.warn('保存对话历史失败', e)
        }
    },
    { deep: true }
)

// 页面加载：恢复历史记录 + 加载 API Key 列表
onMounted(() => {
    try {
        const history = localStorage.getItem(STORAGE_KEY)
        if (history) {
            const parsed = JSON.parse(history)
            if (Array.isArray(parsed)) messages.value = parsed
        }
    } catch (e) {
        console.warn('恢复对话历史失败', e)
        localStorage.removeItem(STORAGE_KEY)
    }
    loadApiKeys()
})
</script>

<style scoped>
.chat-page {
    max-width: 960px;
    margin: 0 auto;
}

/* 工具栏 */
.chat-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 16px;
}

.strategy-select-button,
.model-select-button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
}

.strategy-label,
.current-model {
    font-weight: 500;
}

.dropdown-icon {
    font-size: 10px;
    color: #999;
}

.strategy-option {
    padding: 4px 0;
    min-width: 220px;
}

.strategy-option-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.strategy-option-label {
    font-weight: 500;
}

.strategy-option-desc {
    font-size: 12px;
    color: #999;
    margin-top: 2px;
}

.strategy-active {
    background: #e6f4ff;
}

.reasoning-switch {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 0 8px;
}

.reasoning-icon {
    color: #faad14;
}

.reasoning-label {
    font-size: 14px;
    color: #555;
}

/* 消息列表 */
.message-list {
    max-height: 500px;
    min-height: 200px;
    overflow-y: auto;
    margin-bottom: 16px;
    padding: 16px;
    background: #f5f5f5;
    border-radius: 8px;
}

.message-item {
    margin-bottom: 16px;
    padding: 12px;
    border-radius: 8px;
    background: white;
}

.message-item.user {
    margin-left: 20%;
    background: #e6f7ff;
}

.message-item.assistant {
    margin-right: 20%;
    background: #f6ffed;
}

.message-role {
    font-size: 12px;
    color: #888;
    margin-bottom: 4px;
}

.message-content {
    word-break: break-word;
    line-height: 1.6;
}

.message-content :deep(p) {
    margin: 0 0 8px;
}

.message-content :deep(p:last-child) {
    margin-bottom: 0;
}

.message-content :deep(pre) {
    background: #1e1e1e;
    color: #e6e6e6;
    padding: 12px;
    border-radius: 6px;
    overflow-x: auto;
}

.message-content :deep(code) {
    font-family: 'Fira Code', Consolas, monospace;
    font-size: 13px;
}

.input-area {
    overflow: hidden;
}

/* 模型选择网格 */
.model-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 12px;
}

.model-card {
    border: 1px solid #f0f0f0;
    border-radius: 8px;
    padding: 12px;
    cursor: pointer;
    transition: all 0.2s;
}

.model-card:hover {
    border-color: #91caff;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.model-card.active {
    border-color: #1677ff;
    background: #e6f4ff;
}

.model-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
}

.model-name {
    font-weight: 600;
}

.tag-reasoning {
    font-size: 12px;
    color: #722ed1;
    background: #f9f0ff;
    border: 1px solid #d3adf7;
    border-radius: 4px;
    padding: 0 6px;
}

.model-info {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 12px;
    color: #888;
    margin-bottom: 6px;
}

.model-price {
    color: #fa8c16;
}

.model-desc {
    font-size: 12px;
    color: #999;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}
</style>
