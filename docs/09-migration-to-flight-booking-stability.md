# 从 OnCall Agent 到航旅预订链路稳定性 Agent 的迁移说明

## 文档目标

这份文档记录项目从早期 OnCall Agent 叙事，逐步迁移到当前航旅预订链路稳定性场景的核心变化，重点说明：

1. 为什么最终选择航旅场景
2. 实际修改了哪些层
3. 迁移后的版本具备什么特征

## 为什么选择航旅场景

最终选择航旅预订链路稳定性，而不是停留在更泛化的运维或电商大促场景，原因主要有四点：

1. 场景辨识度更高，能和常见电商稳定性项目区分开。
2. 搜索、预订、出票、退改签和 GDS 接口天然适合串联告警、日志、知识库和报告生成。
3. 既能体现 Java 后端工程能力，也能体现 AI Agent 编排与 RAG 落地。
4. 业务叙事清晰，便于对外开源展示和面试表达。

## 迁移不是只改文案

这次迁移不是把旧业务词替换成“航旅”两个字，而是把提示词、mock 数据、知识库、前端展示和报告输出一起改成统一的航旅稳定性叙事。

## 实际改动层次

### 1. 对话系统提示词迁移

ChatService 从原来的通用运维或旧业务背景，迁移为航旅链路稳定性助手，明确围绕以下问题回答：

1. 航班搜索与报价
2. 订单创建与 PNR 处理
3. 支付与出票
4. 退款与改签
5. GDS 与供应商接口异常

### 2. 多 Agent 报告模板迁移

AiOpsService 中的最终报告模板已经改为航旅版本，聚焦：

1. 活跃风险事件清单
2. 具体链路分析
3. 日志与监控证据
4. 根因与处置建议
5. 整体风险评估

### 3. 巡检接口命名迁移

主巡检接口统一为：

1. /api/flight_guard

同时保留别名接口：

1. /api/campaign_guard
2. /api/ai_ops

这样既统一新叙事，也兼容旧版前端或旧脚本调用。

### 4. mock 告警迁移

QueryMetricsTools 中的 mock 告警已经替换为典型航旅风险事件：

1. FlightSearchLatencySpike
2. TicketingFailureRateRise
3. RefundProcessingDelay

### 5. mock 日志迁移

QueryLogsTools 中的 mock 日志主题迁移为航旅业务链路：

1. gds-gateway
2. booking-transaction
3. payment-settlement
4. ancillary-service

### 6. 知识库内容迁移

aiops-docs 下的文档已经替换为航旅排障知识：

1. flight_search_latency_spike.md
2. ticketing_failure_rate_rise.md
3. refund_processing_delay.md
4. gds_supplier_timeout.md
5. seat_inventory_sync_error.md

### 7. 前端交互迁移

前端页面内容已经改为航旅主题：

1. 页面主标题使用航旅稳定性 Agent 表达
2. 按钮文案围绕链路巡检
3. 输入提示引导用户问航旅相关问题
4. 主巡检请求改为调用 /api/flight_guard
5. 首页新增值班驾驶舱，把业务场景、一键演示和服务端会话状态前置展示

### 8. 轻量部署策略保留

尽管业务场景发生迁移，但为了适配低配 ECS，以下策略被保留：

1. Milvus 可关闭
2. MCP 可关闭
3. 告警与日志可使用 mock 数据
4. 知识检索可使用本地 Markdown fallback

## 迁移后的最终版本特征

当前版本已经具备以下特点：

1. 源码叙事和业务文案统一到航旅场景
2. README 与项目名称统一到 AeroFlow Sentinel
3. Java 包路径统一为 io.github.zhulongqihan.aeroflow.sentinel
4. 启动类统一为 AeroFlowSentinelApplication
5. 项目可以在轻量模式下完成部署和演示
6. 首页具备对面试友好的前端驾驶舱，而不再只是普通聊天框

## 这次迁移的真正价值

这次迁移的价值不是“换了一个名字”，而是把整个系统重新包装成一个更清晰、更垂直、也更适合求职表达的业务故事。

对于面试来说，这样的迁移能体现你三类能力：

1. 理解原项目架构的能力
2. 把技术能力迁移到新业务域的能力
3. 将项目包装成对外可讲述作品的能力

## 对面试最有帮助的一句话

如果面试官问“你这个项目和普通 AI Demo 有什么区别”，你可以回答：

“我不是简单改了 Prompt，而是把告警、日志、知识库、巡检流程、前端交互和部署方式都迁移到了一个新的航旅业务域里，让它真正变成一个可讲清楚、可运行、可展示的工程项目。”