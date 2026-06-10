<template>
    <div class="chat-page">
        <a-card title="AI 对话">
            <template #extra>
                <a-space>
                    <a-select v-model:value="selectedModel" style="width: 200px">
                        <a-select-option value="qwen-plus">通义千问-Plus</a-select-option>
                    </a-select>
                    <a-button @click="clearChat">清空对话</a-button>
                </a-space>
            </template>

            <!-- API Key 选择 -->
            <a-alert v-if="!selectedApiKeyId" message="请先选择一个 API Key" type="warning" show-icon
                style="margin-bottom: 16px" />
            <a-select v-model:value="selectedApiKeyId" placeholder="选择 API Key" style="width: 100%; margin-bottom: 16px"
                @change="loadTokenStats">
                <a-select-option v-for="key in apiKeys" :key="key.id" :value="key.id">
                    {{ key.keyName || '未命名' }} ({{ key.keyValue }})
                </a-select-option>
            </a-select>

            <!-- Token 统计 -->
            <a-statistic title="当前 API Key 已消耗 Token" :value="totalTokens" style="margin-bottom: 16px" />

            <!-- 消息列表 -->
            <div class="message-list" ref="messageListRef">
                <a-empty v-if="messages.length === 0 && !loading" description="开始你的对话吧" />
                <div v-for="(msg, index) in messages" :key="index" :class="['message-item', msg.role]">
                    <div class="message-role">{{ getRoleLabel(msg.role || '') }}</div>
                    <div class="message-content" v-html="formatContent(msg.content || '')"></div>
                </div>
                <!-- 加载中的消息 -->
                <div v-if="loading" class="message-item assistant">
                    <div class="message-role">AI</div>
                    <div class="message-content">
                        <a-spin v-if="!streamingContent" />
                        <span v-html="formatContent(streamingContent || '')"></span>
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
                <a-button type="primary" :loading="loading" :disabled="!userInput.trim() || !selectedApiKeyId"
                    style="margin-top: 8px; float: right" @click="handleSend">
                    发送
                </a-button>
            </div>
        </a-card>
    </div>
</template>

<script lang="ts" setup>
import { getMyTokenStats, listMyApiKeys } from '@/api/apiKeyController'
import { message } from 'ant-design-vue'
import { nextTick, onMounted, ref, watch } from 'vue'
import { debounce } from 'lodash-es'
import { marked } from 'marked'

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

// 当前选择的模型
const selectedModel = ref('qwen-plus')
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
const messageListRef = ref()
// 最近一次的错误信息（用于展示重试入口）
const lastError = ref('')

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
    // 调用防抖后的函数
    debouncedSend()
}

// 带重试的 fetch：仅对网络异常（fetch reject）做指数退避重试，HTTP 错误不重试
async function fetchWithRetry(
    url: string,
    options: RequestInit,
    retries = 3
): Promise<Response> {
    let lastErr: unknown
    for (let i = 0; i < retries; i++) {
        try {
            return await fetch(url, options)
        } catch (error) {
            lastErr = error
            // 最后一次失败则抛出，否则退避后重试（1s、2s、3s...）
            if (i < retries - 1) {
                await new Promise((resolve) => setTimeout(resolve, 1000 * (i + 1)))
            }
        }
    }
    throw lastErr
}

// 发送消息（使用 function 声明以支持提升，避免 TDZ 错误）
async function handleSend() {
    // 防止重复提交
    if (loading.value) return
    if (!userInput.value.trim() || !selectedApiKeyId.value) return

    const content = userInput.value.trim()
    // 先清空输入框，避免重复发送
    userInput.value = ''
    lastError.value = ''

    const userMessage: API.ChatMessage = {
        role: 'user',
        content,
    }
    messages.value.push(userMessage)

    await sendCompletion()
}

// 真正发起对话请求（独立出来，便于重试）
async function sendCompletion() {
    if (!selectedApiKeyId.value) return

    loading.value = true
    streamingContent.value = ''
    lastError.value = ''

    // 滚动到底部
    await nextTick()
    scrollToBottom()

    try {
        const chatRequest: API.ChatRequest = {
            model: selectedModel.value,
            messages: messages.value,
            stream: true,
        }

        // 注意：生产环境建议使用相对路径或环境变量，避免硬编码 localhost
        const response = await fetchWithRetry(
            `/api/internal/chat/completions?apiKeyId=${selectedApiKeyId.value}`,
            {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
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
        if (buffer) {
            consume(buffer)
        }

        // 完成
        messages.value.push({
            role: 'assistant',
            content: assistantContent,
        })
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
    if (loading.value) return
    if (messages.value.length === 0) return
    sendCompletion()
}

// 保存到本地存储（仅保留最近 MAX_STORED_MESSAGES 条，并捕获写入异常）
watch(
    messages,
    (newMessages) => {
        try {
            const toStore = newMessages.slice(-MAX_STORED_MESSAGES)
            localStorage.setItem(STORAGE_KEY, JSON.stringify(toStore))
        } catch (e) {
            // localStorage 可能超额或被禁用，忽略即可
            console.warn('保存对话历史失败', e)
        }
    },
    { deep: true }
)

// 内容格式化：先把转义的 \n 还原成真实换行，再用 marked 渲染 Markdown
const formatContent = (content: string): string => {
    if (!content) return ''
    try {
        const unescaped = content.replace(/\\n/g, '\n')
        return marked.parse(unescaped) as string
    } catch (e) {
        console.warn('Markdown 解析失败', e)
        return content
    }
}

// 创建防抖函数
const debouncedSend = debounce(handleSend, 300)

// 滚动到底部
const scrollToBottom = () => {
    if (messageListRef.value) {
        messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
}

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

// 页面加载：恢复历史记录 + 加载 API Key 列表
onMounted(() => {
    try {
        const history = localStorage.getItem(STORAGE_KEY)
        if (history) {
            const parsed = JSON.parse(history)
            if (Array.isArray(parsed)) {
                messages.value = parsed
            }
        }
    } catch (e) {
        // 历史数据损坏时清除，避免反复报错
        console.warn('恢复对话历史失败', e)
        localStorage.removeItem(STORAGE_KEY)
    }
    loadApiKeys()
})
</script>

<style scoped>
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

.input-area {
    overflow: hidden;
}
</style>
