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
    /** API Key ID（可选，不传则统计全部） */
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

  type PageApiKeyVO = {
    records?: ApiKeyVO[]
    pageNumber?: number
    pageSize?: number
    maxPageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
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
