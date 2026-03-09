# 工具体系与外部集成

## 文档目标

这份文档说明系统如何通过工具调用连接监控、日志和知识文档三类证据源。读完后应该能回答：

1. 系统里有哪些本地工具
2. 本地工具和 MCP 工具有什么区别
3. Milvus 开关如何影响知识检索链路
4. 为什么工具层是业务迁移和工程扩展的关键位置

## 关键文件

1. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/DateTimeTools.java
2. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/InternalDocsTools.java
3. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/QueryMetricsTools.java
4. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/QueryLogsTools.java
5. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/ChatService.java

## 两类工具体系

当前系统中的工具可以分成两类：

### 1. 本地 Method Tools

这些工具由 Spring Bean 直接注册给 Agent，优点是稳定、可控、易于本地运行。

### 2. MCP Tools

这些工具通过 ToolCallbackProvider 注入，适合接入远程平台能力，例如腾讯云 CLS MCP。

这种双轨结构的好处是：

1. Demo 环境可以只用本地工具。
2. 真实环境可以逐步接入远程能力。
3. 同一套 Agent 编排逻辑不需要因环境切换而重写。

## 工具一：DateTimeTools

DateTimeTools 的职责最简单，就是向 Agent 提供当前时间。虽然看起来基础，但它解决了两件事：

1. 避免模型自己编造时间。
2. 为巡检报告和问题回答提供统一时间参考。

## 工具二：InternalDocsTools

InternalDocsTools 是知识检索入口，它有两种工作模式：

### Milvus 开启时

1. 调用 VectorSearchService 做向量相似检索。
2. 返回最相关的文档片段。

### Milvus 关闭时

1. 回退到 aiops-docs 目录下的本地 Markdown 文档。
2. 通过关键词匹配做轻量兜底检索。

这个设计非常关键，因为它保证系统在没有向量数据库时仍然具备知识检索能力。

## 工具三：QueryMetricsTools

QueryMetricsTools 负责查询活动告警。它也分成两种模式：

### 真实模式

1. 调用 Prometheus 的 /api/v1/alerts。
2. 读取活动告警并做简化转换。

### Mock 模式

返回预设的航旅风险事件，例如：

1. FlightSearchLatencySpike
2. TicketingFailureRateRise
3. RefundProcessingDelay

Mock 模式的价值不是“造数据”，而是让多 Agent 流程在低配环境中也能完成闭环演示。

## 工具四：QueryLogsTools

QueryLogsTools 负责日志证据，它聚焦四类业务主题：

1. gds-gateway
2. booking-transaction
3. payment-settlement
4. ancillary-service

在 mock 模式下，它会生成与风险事件相匹配的日志片段；在真实场景下，则可以通过 MCP 接入腾讯云 CLS。

## 为什么工具层是关键扩展位

工具层是业务迁移最容易着力的地方，因为：

1. Prompt 可以换领域，但真正决定“能不能落地”的是工具能力。
2. 工具决定了 Agent 能看到哪些真实证据。
3. 新增业务场景时，往往只需要替换部分工具和知识文档，而不一定重写整体架构。

## 关键设计决策

1. 内部文档检索必须支持 Milvus fallback。
2. 告警和日志都必须支持 mock 模式，保证 demo 可运行。
3. 工具输出以结构化信息为主，方便 Agent 消费。

## 当前局限

1. CLS 真实查询当前仍以 MCP 方式为主，本地 Java 直连尚未补齐。
2. 告警查询目前更多是列表级结果，缺少关联分析能力。
3. 工具层缺少统一性能埋点和可观测性指标。

## 简历表达建议

### 项目亮点总结

构建多源工具体系，将监控告警、业务日志和内部知识文档统一抽象为 Agent 可调用工具，并通过 mock 与 fallback 机制保证系统在低配环境下仍可完整演示。

### 面试常见追问

1. 为什么要把工具分成本地工具和 MCP 工具
2. 为什么 InternalDocsTools 必须支持 fallback
3. mock 工具在工程上怎么避免变成“假功能”