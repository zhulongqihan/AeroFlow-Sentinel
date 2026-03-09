# AeroFlow Sentinel 项目总览

## 文档目标

这份文档用于快速建立对项目的整体认知，回答四个核心问题：

1. 这个项目现在到底是什么
2. 它由哪些关键模块组成
3. 它的真实调用链路是什么
4. 它为什么适合作为 Java 后端 + AI Agent 方向的面试项目

## 项目定位

AeroFlow Sentinel 是一个面向航旅预订链路稳定性治理的 AI Agent 系统。它不是单纯的聊天机器人，而是把对话、工具调用、知识检索、多 Agent 协作和 Markdown 报告输出串成闭环的工程化系统。

当前项目包含两条主能力线：

1. 智能问答助手：回答航旅预订链路、故障排查手册、稳定性治理和运行预案相关问题。
2. 链路巡检 Agent：围绕搜索、下单、出票、退款改签和 GDS 接口等链路，自动分析风险并输出结构化报告。

## 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Java 17 |
| 后端框架 | Spring Boot 3.2.0 |
| Agent 框架 | Spring AI + Spring AI Alibaba |
| 大模型 | DashScope Chat Model |
| 向量能力 | DashScope Embedding |
| 向量数据库 | Milvus 2.6.10，可选 |
| 风险证据 | Prometheus、CLS MCP 或 mock 数据 |
| 前端 | 原生 HTML + CSS + JavaScript |
| 通信方式 | REST + SSE |

## 目录结构

```text
aeroflow-sentinel/
├── src/main/java/io/github/zhulongqihan/aeroflow/sentinel/
│   ├── controller/        # API 入口
│   ├── service/           # 核心业务服务
│   ├── agent/tool/        # Agent 可调用工具
│   ├── config/            # 配置层
│   ├── client/            # Milvus 客户端初始化
│   ├── constant/          # 常量定义
│   ├── dto/               # 请求与响应对象
│   ├── tool/              # 工具类入口
│   └── AeroFlowSentinelApplication.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-demo.yml
│   └── static/
├── aiops-docs/            # 航旅领域知识库文档
├── docs/                  # 内部架构与面试材料
├── vector-database.yml
└── Makefile
```

## 系统分层

### 1. 表现层

前端页面现在不只是输入框和消息区，还增加了一个“值班驾驶舱”，专门用于把项目价值显性化：

1. 顶部显示当前模式、服务端会话状态、本地历史数量和最近动作。
2. 提供一键演示场景，面试时无需临场组织 prompt。
3. 用操作时间线展示“用户动作 -> Agent 响应 -> 会话沉淀”的闭环。

### 2. 接口层

控制器负责接收 HTTP 请求、校验参数、组织响应格式，并在流式场景下通过 SSE 返回增量内容。

### 3. 业务层

业务层是项目核心：

1. ChatService 负责普通对话 Agent。
2. AiOpsService 负责多 Agent 巡检编排。
3. RagService 负责知识检索增强问答。
4. Vector 系列服务负责文档切片、向量化、入库和检索。

### 4. 工具层

工具层向模型暴露时间、内部知识、监控告警和日志查询等能力，是 Agent 可以落地执行的关键。

### 5. 基础设施层

DashScope、Milvus、Prometheus、CLS MCP 和 Docker Compose 构成底层运行支撑。

## 三条关键调用链

### 链路一：普通对话

```text
用户输入问题
  -> ChatController
  -> ChatService 构建 ReactAgent
  -> ReactAgent 判断是否调用工具
  -> DashScope 生成回答
  -> 返回普通响应或 SSE 流式响应
```

### 链路二：航旅链路巡检

```text
用户触发巡检
  -> ChatController.aiOps()
  -> AiOpsService.executeAiOpsAnalysis()
  -> SupervisorAgent 调度 PlannerAgent 和 ExecutorAgent
  -> 工具查询告警、日志和内部文档
  -> Planner 输出最终 Markdown 报告
  -> SSE 流式返回前端
```

### 链路三：知识库上传与检索

```text
上传文档
  -> FileUploadController
  -> VectorIndexService.indexSingleFile()
  -> DocumentChunkService 分片
  -> VectorEmbeddingService 向量化
  -> 写入 Milvus

用户检索
  -> VectorSearchService 搜索相似片段
  -> RagService 或 InternalDocsTools 组装上下文
  -> 模型生成基于证据的回答
```

## 当前部署现实

项目已经适配低配 ECS 场景：

1. demo profile 下可以关闭 Milvus。
2. 可以使用本地 Markdown 作为知识检索兜底。
3. 可以使用 mock 告警和 mock 日志完成演示。
4. 已经具备线上可访问演示地址。
5. 前端驾驶舱不依赖额外图表库，低配 ECS 上也能直观看到系统能力。

## 这个项目的面试价值

它适合作为 Java 后端 + AI Agent 面试项目，原因在于：

1. 有完整后端分层，而不是只写 Prompt。
2. 有多 Agent 编排，不是单轮问答 Demo。
3. 有 RAG 和向量检索链路，能讲清数据流。
4. 有轻量部署方案，说明具备工程落地意识。
5. 有清晰业务场景，便于向面试官说明项目价值和边界。

## 当前局限

1. 真实 CLS 查询仍主要依赖 MCP 或 mock，Java 本地直连仍可继续增强。
2. 会话持久化当前使用单机文件方案，适合演示和单实例部署，不适合多实例共享。
3. 前端驾驶舱目前以卡片和时间线为主，可继续补成巡检历史对比与趋势视图。
4. 轻量部署模式更适合演示，不等于完整生产方案。

## 一句话总结

AeroFlow Sentinel 的核心价值，不是“接了一个大模型”，而是把 Java 后端分层、工具调用、RAG、多 Agent 编排和低成本部署真正组合成了一个可以展示、可以讲清楚、可以继续扩展的工程项目。