# ChatService 与 ReactAgent 机制

## 文档目标

这份文档解释普通问答链路是如何工作的，重点说明：

1. ChatService 在整个系统中的职责
2. ReactAgent 是如何被构建和执行的
3. 系统提示词与历史上下文是如何组织的
4. 方法工具和 MCP 工具是如何组合的

## 关键文件

1. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/ChatService.java
2. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/DateTimeTools.java
3. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/InternalDocsTools.java
4. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/QueryMetricsTools.java
5. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/QueryLogsTools.java

## ChatService 的四个核心职责

ChatService 不是简单的模型调用封装，它主要承担四项工作：

1. 创建 DashScope API 与 ChatModel。
2. 拼接系统提示词和历史消息。
3. 组织本地工具与远程 MCP 工具。
4. 创建并执行 ReactAgent。

## 模型创建逻辑

ChatService 通过 createDashScopeApi() 和 createChatModel() 创建模型实例，标准问答使用默认参数：

1. temperature=0.7
2. maxToken=2000
3. topP=0.9

这些参数的取舍是：

1. 保留一定回答灵活性。
2. 不让普通问答过于发散。
3. 让工具调用场景下的回答仍然具备可读性。

## 系统提示词如何构建

系统提示词分两层：

### 第一层：固定能力说明

这一层告诉模型：

1. 你是面向航旅预订链路稳定性治理的智能助手。
2. 时间问题用 getCurrentDateTime。
3. 内部文档问题用 queryInternalDocs。
4. 监控风险问题用 queryPrometheusAlerts。
5. 日志排查优先使用日志工具或 MCP。

### 第二层：历史对话上下文

如果会话里已有历史消息，ChatService 会把历史对话按“用户/助手”格式拼接到系统提示词里，再让模型回答当前问题。

这样做的好处是：

1. 普通对话天然支持多轮上下文。
2. 不需要前端自行维护复杂 Prompt。
3. 后端可以统一控制上下文窗口和内容格式。

## 工具体系如何组织

当前系统有两类工具：

### 1. Method Tools

这些工具由 Spring Bean 直接暴露给 Agent：

1. DateTimeTools
2. InternalDocsTools
3. QueryMetricsTools
4. QueryLogsTools，可选

### 2. MCP Tools

这些工具通过 ToolCallbackProvider 动态注入，适合接入外部平台能力，例如腾讯云 CLS MCP。

## 为什么要区分两类工具

1. 本地方法工具更适合稳定、可控的内置能力。
2. MCP 工具更适合远程平台和可插拔扩展。
3. 两者组合让系统既能本地运行，也能外接真实平台。

## ReactAgent 的创建流程

```text
创建 DashScope API
  -> 创建 ChatModel
  -> 构建系统提示词
  -> 组合 Method Tools
  -> 注入 MCP ToolCallbacks
  -> ReactAgent.builder()
  -> 返回可执行 Agent
```

在普通问答场景中，ReactAgent 的优势是：

1. 能自动决定是否需要调用工具。
2. 适合“提问 -> 检索工具 -> 回答”的单链路任务。
3. 相比多 Agent，更轻量，控制成本更低。

## 执行方式

ChatService 提供两种执行模式：

1. executeChat()：同步返回完整答案。
2. agent.stream()：由控制器在流式接口中订阅增量内容。

这种设计意味着执行模式可以在控制器层切换，而不需要重新实现一套业务逻辑。

## 关键设计决策

1. 普通问答用 ReactAgent，而不是直接上多 Agent。
2. 系统提示词由后端统一拼接，而不是让前端拼 Prompt。
3. 本地工具与 MCP 工具同时存在，以兼顾演示和真实接入。

## 当前局限

1. 系统提示词仍然是字符串拼接方式，结构化程度有限。
2. MCP 工具可见性依赖外部配置，不同环境下表现不完全一致。
3. 普通问答链路缺少对工具调用耗时与效果的显式监控。

## 简历表达建议

### 项目亮点总结

封装基于 Spring AI Alibaba 的 ReactAgent 对话服务，统一完成模型创建、历史上下文拼接、本地工具与 MCP 工具注入，并同时支持同步问答与 SSE 流式输出。

### 面试常见追问

1. 为什么普通问答不直接用多 Agent
2. 为什么系统提示词要放在后端拼接
3. Method Tools 和 MCP Tools 在工程上有什么区别