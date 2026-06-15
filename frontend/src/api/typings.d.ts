declare namespace API {
  type ApiKeyCreateRequest = {
    /** Key名称/备注 */
    keyName?: string
  }

  type ApiKeyVO = {
    /** id */
    id?: number
    /** API Key值 */
    keyValue?: string
    /** Key名称/备注 */
    keyName?: string
    /** 状态 */
    status?: string
    /** 已使用Token总数 */
    totalTokens?: number
    /** 最后使用时间 */
    lastUsedTime?: string
    /** 创建时间 */
    createTime?: string
  }

  type chatCompletionsParams = {
    /** API Key ID */
    apiKeyId: number
  }

  type ChatMessage = {
    /** 角色：system/user/assistant */
    role?: string
    /** 消息内容 */
    content?: string
  }

  type ChatRequest = {
    /** 最大生成Token数 */
    max_tokens?: number
    /** 是否启用深度思考 */
    enable_reasoning?: boolean
    /** 路由策略类型（auto/cost_first/latency_first/round_robin/fixed），为空时由服务端按是否指定模型自动决定 */
    routing_strategy?: string
    /** 模型名称（如：qwen-plus） */
    model?: string
    /** 消息列表 */
    messages?: ChatMessage[]
    /** 是否流式返回 */
    stream?: boolean
    /** 温度参数（0-1） */
    temperature?: number
  }

  type Choice = {
    /** 索引 */
    index?: number
    /** 聊天消息 */
    message?: ChatMessage
    /** 结束原因 */
    finishReason?: string
  }

  type DeleteRequest = {
    /** id */
    id?: number
  }

  type getMyTokenStatsParams = {
    /** API Key ID（可选） */
    apiKeyId?: number
  }

  type listMyApiKeysParams = {
    /** 页码 */
    pageNum: number
    /** 页大小 */
    pageSize: number
  }

  type LoginUserVO = {
    /** id */
    id?: number
    /** 账号 */
    userAccount?: string
    /** 用户昵称 */
    userName?: string
    /** 用户头像 */
    userAvatar?: string
    /** 用户简介 */
    userProfile?: string
    /** 用户角色：user/admin */
    userRole?: string
    /** 创建时间 */
    createTime?: string
    /** 更新时间 */
    updateTime?: string
  }

  type ModelAddRequest = {
    /** 提供者id */
    providerId?: number
    /** 模型标识（如：qwen-plus） */
    modelKey?: string
    /** 模型显示名称 */
    modelName?: string
    /** 模型类型：chat/embedding/image/audio */
    modelType?: string
    /** 模型描述 */
    description?: string
    /** 上下文长度限制 */
    contextLength?: number
    /** 输入价格（元/千Token） */
    inputPrice?: number
    /** 输出价格（元/千Token） */
    outputPrice?: number
    /** 优先级（越大越优先） */
    priority?: number
    /** 默认超时时间（毫秒） */
    defaultTimeout?: number
    /** 是否支持深度思考：0=不支持，1=支持 */
    supportReasoning?: number
    /** 能力标签（JSON数组） */
    capabilities?: string
  }

  type ModelQueryRequest = {
    /** 当前页 */
    current?: number
    /** 页面大小 */
    pageSize?: number
    /** 排序字段 */
    sortField?: string
    /** 排序顺序（默认：升序） */
    sortOrder?: string
    /** 模型名称（模糊查询） */
    modelName?: string
    /** 模型类型：chat/embedding/image/audio */
    modelType?: string
    /** 状态：active/inactive/deprecated */
    status?: string
    /** 提供者id */
    providerId?: number
  }

  type ModelUpdateRequest = {
    /** id */
    id?: number
    /** 模型显示名称 */
    modelName?: string
    /** 模型描述 */
    description?: string
    /** 上下文长度限制 */
    contextLength?: number
    /** 输入价格（元/千Token） */
    inputPrice?: number
    /** 输出价格（元/千Token） */
    outputPrice?: number
    /** 状态：active/inactive/deprecated */
    status?: string
    /** 优先级（越大越优先） */
    priority?: number
    /** 默认超时时间（毫秒） */
    defaultTimeout?: number
    /** 是否支持深度思考：0=不支持，1=支持 */
    supportReasoning?: number
    /** 能力标签（JSON数组） */
    capabilities?: string
  }

  type ModelVO = {
    /** id */
    id?: number
    /** 提供者id */
    providerId?: number
    /** 提供者显示名称 */
    providerDisplayName?: string
    /** 模型标识（如：qwen-plus） */
    modelKey?: string
    /** 模型显示名称 */
    modelName?: string
    /** 模型类型：chat/embedding/image/audio */
    modelType?: string
    /** 模型描述 */
    description?: string
    /** 上下文长度限制 */
    contextLength?: number
    /** 输入价格（元/千Token） */
    inputPrice?: number
    /** 输出价格（元/千Token） */
    outputPrice?: number
    /** 状态：active/inactive/deprecated */
    status?: string
    /** 健康状态：healthy/unhealthy/degraded/unknown */
    healthStatus?: string
    /** 平均延迟（毫秒） */
    avgLatency?: number
    /** 成功率（百分比） */
    successRate?: number
    /** 综合得分（越低越好） */
    score?: number
    /** 优先级（越大越优先） */
    priority?: number
    /** 默认超时时间（毫秒） */
    defaultTimeout?: number
    /** 是否支持深度思考：0=不支持，1=支持 */
    supportReasoning?: number
    /** 能力标签（JSON数组） */
    capabilities?: string
    /** 创建时间 */
    createTime?: string
    /** 更新时间 */
    updateTime?: string
  }

  type PageApiKeyVO = {
    records?: ApiKeyVO[]
    pageNumber?: number
    pageSize?: number
    maxPageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PageModelVO = {
    records?: ModelVO[]
    pageNumber?: number
    pageSize?: number
    maxPageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PageProviderVO = {
    records?: ProviderVO[]
    pageNumber?: number
    pageSize?: number
    maxPageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type ProviderAddRequest = {
    /** 提供者名称（如：qwen/zhipu/deepseek） */
    providerName?: string
    /** 显示名称（如：通义千问/智谱AI/DeepSeek） */
    displayName?: string
    /** API基础URL */
    baseUrl?: string
    /** API密钥 */
    apiKey?: string
    /** 状态：active/inactive/maintenance */
    status?: string
    /** 优先级（越大越优先） */
    priority?: number
    /** 额外配置（JSON格式） */
    config?: string
  }

  type ProviderQueryRequest = {
    /** 当前页 */
    current?: number
    /** 页面大小 */
    pageSize?: number
    /** 排序字段 */
    sortField?: string
    /** 排序顺序（默认：升序） */
    sortOrder?: string
    /** 显示名称（模糊查询） */
    displayName?: string
    /** 健康状态：healthy/unhealthy/degraded/unknown */
    healthStatus?: string
    /** 状态：active/inactive/maintenance */
    status?: string
  }

  type ProviderUpdateRequest = {
    /** id */
    id?: number
    /** 显示名称（如：通义千问/智谱AI/DeepSeek） */
    displayName?: string
    /** API基础URL */
    baseUrl?: string
    /** API密钥（为空时不更新） */
    apiKey?: string
    /** 状态：active/inactive/maintenance */
    status?: string
    /** 优先级（越大越优先） */
    priority?: number
    /** 额外配置（JSON格式） */
    config?: string
  }

  type ProviderVO = {
    /** id */
    id?: number
    /** 提供者名称（如：qwen/zhipu/deepseek） */
    providerName?: string
    /** 显示名称（如：通义千问/智谱AI/DeepSeek） */
    displayName?: string
    /** API基础URL */
    baseUrl?: string
    /** API密钥（脱敏显示） */
    apiKey?: string
    /** 状态：active/inactive/maintenance */
    status?: string
    /** 健康状态：healthy/unhealthy/degraded/unknown */
    healthStatus?: string
    /** 平均延迟（毫秒） */
    avgLatency?: number
    /** 成功率（百分比） */
    successRate?: number
    /** 优先级（越大越优先） */
    priority?: number
    /** 创建时间 */
    createTime?: string
    /** 更新时间 */
    updateTime?: string
  }

  type ResultApiKeyVO = {
    code?: number
    msg?: string
    data?: ApiKeyVO
  }

  type ResultBoolean = {
    code?: number
    msg?: string
    data?: boolean
  }

  type ResultListProviderVO = {
    code?: number
    msg?: string
    data?: ProviderVO[]
  }

  type ResultLoginUserVO = {
    code?: number
    msg?: string
    data?: LoginUserVO
  }

  type ResultLong = {
    code?: number
    msg?: string
    data?: number
  }

  type ResultPageApiKeyVO = {
    code?: number
    msg?: string
    data?: PageApiKeyVO
  }

  type ResultPageModelVO = {
    code?: number
    msg?: string
    data?: PageModelVO
  }

  type ResultPageProviderVO = {
    code?: number
    msg?: string
    data?: PageProviderVO
  }

  type Usage = {
    /** 输入Token数 */
    promptTokens?: number
    /** 输出Token数 */
    completionTokens?: number
    /** 总Token数 */
    totalTokens?: number
  }

  type UserLoginRequest = {
    /** 账号 */
    userAccount?: string
    /** 密码 */
    userPassword?: string
  }

  type UserRegisterRequest = {
    /** 账号 */
    userAccount?: string
    /** 密码 */
    userPassword?: string
    /** 确认密码 */
    checkPassword?: string
  }
}
