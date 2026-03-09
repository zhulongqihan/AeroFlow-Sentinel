# 请求入口与调用链

## 文档目标

这份文档聚焦系统请求如何进入后端，并沿着不同链路被分发到对话、巡检或知识处理流程。读完后应该能回答：

1. 项目暴露了哪些核心接口
2. 普通问答、流式问答和链路巡检分别走哪条调用链
3. SSE 流式响应是如何组织的
4. 会话历史是如何管理和持久化的

## 关键文件

1. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/controller/ChatController.java
2. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/controller/FileUploadController.java
3. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/controller/MilvusCheckController.java
4. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/ChatSessionService.java

## 接口总览

| 接口 | 方法 | 功能 |
|------|------|------|
| /api/chat | POST | 普通对话，返回完整结果 |
| /api/chat_stream | POST | 流式对话，SSE 输出 |
| /api/flight_guard | POST | 航旅链路巡检，SSE 输出 |
| /api/campaign_guard | POST | 兼容旧入口，行为等同 /api/flight_guard |
| /api/ai_ops | POST | 兼容旧入口，行为等同 /api/flight_guard |
| /api/chat/clear | POST | 清空指定会话历史 |
| /api/chat/session/{sessionId} | GET | 查询会话信息 |
| /api/upload | POST | 上传知识文档 |
| /milvus/health | GET | 检查 Milvus 连接状态 |

## ChatController 的职责边界

ChatController 是统一 API 入口，但它本身不承担复杂业务推理。它的主要职责有四类：

1. 参数校验
2. 会话获取与响应包装
3. 创建普通响应或 SSE 通道
4. 调用 ChatService 或 AiOpsService 完成真正的 Agent 工作流

这是一种典型的“薄控制器、厚服务层”设计。

## 调用链一：普通对话

```text
前端发送问题
  -> ChatController.chat()
  -> ChatSessionService.getOrCreateSession()
  -> 读取历史消息
  -> ChatService.createDashScopeApi()
  -> ChatService.createStandardChatModel()
  -> ChatService.buildSystemPrompt(history)
  -> ChatService.createReactAgent()
  -> ChatService.executeChat()
  -> ChatSessionService.appendMessagePair()
  -> 返回 ApiResponse<ChatResponse>
```

这一条链路的特点是：

1. 走同步接口，适合短问答。
2. 工具调用对前端透明，前端只接收最终答案。
3. 历史消息先被拼进系统提示词，再交给 ReactAgent 执行。

## 调用链二：流式对话

```text
前端请求 /api/chat_stream
  -> 创建 SseEmitter
  -> 在线程池中执行任务
  -> ChatSessionService.getOrCreateSession()
  -> ChatService 构建 ReactAgent
  -> agent.stream(question) 返回 Flux<NodeOutput>
  -> 订阅 Flux
  -> 持续把增量内容包装成 SseMessage.content
  -> 完成后保存会话并发送 done
```

这一条链路的特点是：

1. 适合长回答或需要逐步显示的场景。
2. 只把模型增量文本推给前端，工具执行和 hook 事件主要记录日志。
3. 对用户体验更友好，减少“长时间无响应”的感知。

## 调用链三：航旅链路巡检

```text
前端触发链路巡检
  -> ChatController.aiOps()
  -> 创建长超时 SseEmitter
  -> 构建更保守的 ChatModel
  -> 获取 MCP ToolCallbacks
  -> AiOpsService.executeAiOpsAnalysis()
  -> 返回 OverAllState
  -> AiOpsService.extractFinalReport()
  -> 将最终 Markdown 报告按块推给前端
  -> 发送 done
```

这一条链路和普通问答最大的不同，是它不是单次回答，而是一个多 Agent 协作过程。它更像一次自动化巡检任务，而不是一轮聊天。

## 文件上传链路

```text
上传文件
  -> FileUploadController
  -> 校验文件名与扩展名
  -> 保存到 uploads 目录
  -> Milvus 开启时调用 VectorIndexService.indexSingleFile()
  -> Milvus 关闭时仅保留本地文件
  -> 返回统一上传结果
```

这意味着上传能力在轻量模式下仍然成立，只是不会触发向量入库。

## 会话管理设计

当前版本已经从纯内存会话升级为可选持久化设计，核心由 ChatSessionService 负责。

### 当前策略

1. 会话按 sessionId 组织。
2. 每次保存一对消息：用户输入 + 助手回答。
3. 默认只保留最近 6 对消息窗口。
4. 会话可以落盘到本地 JSON 文件。
5. 服务重启后可重新加载历史会话。

### 为什么这样做

1. 保留上下文对多轮问答有帮助。
2. 控制窗口大小可以降低 Prompt 膨胀成本。
3. 本地持久化不依赖数据库，更容易部署到现有 ECS。

## 响应模型设计

### 普通接口

所有普通 HTTP 接口统一使用 ApiResponse<T> 包装：

1. code
2. message
3. data

### SSE 接口

所有流式接口统一使用 SseMessage 包装：

1. type=content
2. type=error
3. type=done

这种统一格式让前端消费逻辑保持稳定，不需要对每个流式接口单独解析。

## 关键设计决策

1. 普通问答和巡检分两套接口，而不是用一个接口兼容所有行为。
2. 对话和巡检都通过 ChatController 暴露，但真正逻辑下沉到 Service 层。
3. 会话管理从一开始就和业务逻辑解耦，便于后续替换成数据库版本。

## 当前局限

1. 当前会话持久化是本地文件级方案，更适合单机部署。
2. 文件上传与向量入库不是强事务关系。
3. SSE 主要输出文本结果，缺少结构化执行轨迹回放。

## 简历表达建议

### 项目亮点总结

设计统一的 REST + SSE 后端交互入口，支持普通问答、流式问答和航旅链路巡检三类模式，并通过独立会话服务实现上下文窗口控制与本地持久化。

### 面试常见追问

1. 为什么同时保留 REST 和 SSE 两种模式
2. 为什么会话持久化没有直接上数据库
3. 控制器和服务层的职责是如何划分的