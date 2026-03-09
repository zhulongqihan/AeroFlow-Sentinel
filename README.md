# AeroFlow Sentinel

> 一个面向航旅预订稳定性分析、故障研判与运维手册辅助排障的 AI Agent 系统。

## 项目简介

AeroFlow Sentinel 是一个基于 Java 17、Spring Boot 和 Spring AI Alibaba 构建的航旅场景 AI Agent 项目，当前聚焦于“航旅预订链路稳定性治理”。

系统将两类能力整合在同一个工程中：

1. 智能对话助手：围绕航旅预订链路、故障处理手册、排障流程提供多轮问答。
2. 稳定性巡检 Agent：通过 Supervisor-Planner-Executor 多 Agent 协同，对搜索、下单、出票、退款改签、GDS 或供应商网关等链路进行分析并输出 Markdown 报告。

当前仓库与线上演示版本保持一致，并且已经适配低配 ECS 的轻量部署模式。

英文说明见 README_EN.md。

## 业务范围

当前覆盖的核心业务链路包括：

1. 航班搜索与报价
2. 订单创建与 PNR 处理
3. 支付与出票
4. 退款与改签履约
5. GDS 与供应商网关集成
6. 选座、行李、保险等附加服务

## 核心能力

- 支持工具调用的多轮对话
- 支持 SSE 流式输出
- 支持多 Agent 巡检与 Markdown 分析报告输出
- 支持前端值班驾驶舱、一键演示场景和操作时间线展示
- 支持基于 RAG 的知识检索，以及 Milvus 关闭时的本地 Markdown 兜底检索
- 支持会话历史窗口控制与本地文件持久化
- 支持 Demo 模式下的模拟告警与模拟日志
- 支持低成本、低规格服务器部署

## 系统架构

### 对话层

- POST /api/chat
- POST /api/chat_stream

由 ChatController 和 ChatService 负责，底层通过 ReactAgent 组合航旅领域工具能力。前端值班驾驶舱会直观展示当前模式、服务端会话同步状态、本地历史条数和典型演示场景。

### 巡检层

- POST /api/flight_guard

为了兼容旧版前端和历史演示链路，仍保留以下别名路由：

- POST /api/campaign_guard
- POST /api/ai_ops

巡检接口会触发 Supervisor-Planner-Executor 多 Agent 工作流，并以流式 Markdown 的形式输出航旅稳定性分析报告。

### 知识层

- Milvus 启用时，通过 InternalDocsTools 检索向量知识库
- Milvus 关闭时，自动回退到 aiops-docs 目录下的本地 Markdown 手册

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 主语言 |
| Spring Boot | 3.2.0 | 后端框架 |
| Spring AI Alibaba | 1.1.0.0-RC2 | Agent 框架 |
| DashScope | 2.17.0 | 大模型与向量服务 |
| Milvus | 2.6.10 | 可选向量数据库 |
| Prometheus | - | 告警源或 Demo 告警源 |
| Tencent CLS MCP | - | 可选远程日志工具接入 |

## 仓库结构

```text
aeroflow-sentinel/
├── src/main/java/io/github/zhulongqihan/aeroflow/sentinel/
│   ├── agent/tool/
│   ├── client/
│   ├── config/
│   ├── constant/
│   ├── controller/
│   ├── dto/
│   ├── service/
│   ├── tool/
│   └── AeroFlowSentinelApplication.java
├── src/main/resources/
│   ├── static/
│   ├── application.yml
│   └── application-demo.yml
├── aiops-docs/
├── docs/
├── vector-database.yml
└── Makefile
```

当前仓库品牌、Maven 坐标、启动类和构建产物名已经统一为 AeroFlow Sentinel。

## 接口说明

### 对话接口

```bash
POST /api/chat
POST /api/chat_stream
```

### 巡检接口

```bash
POST /api/flight_guard
```

### 文件与会话接口

```bash
POST /api/upload
POST /api/chat/clear
GET /api/chat/session/{sessionId}
GET /milvus/health
```

当前版本中，会话历史默认可持久化到本地 JSON 文件，服务重启后仍可恢复最近会话上下文。

## Demo 模式

对于 2C2G 等低配机器，推荐使用 demo profile 运行：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

在 Demo 模式下：

- Milvus 默认关闭
- MCP 客户端默认关闭
- Prometheus 告警采用 mock 数据
- CLS 日志采用 mock 数据
- 内部知识检索使用本地 Markdown 兜底

## 构建与运行

### 1. 配置环境变量

Linux 或 macOS:

```bash
export DASHSCOPE_API_KEY=your-api-key
```

PowerShell:

```powershell
$env:DASHSCOPE_API_KEY="your-api-key"
```

### 2. 打包

```bash
mvn clean package -DskipTests
```

### 3. 运行 Demo 模式

```bash
java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

### 4. 可选的完整模式

```bash
docker compose up -d -f vector-database.yml
java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar
```

## 调用示例

### 普通问答

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"出票失败率升高时应该先检查什么？"}'
```

### 巡检分析

```bash
curl -N -X POST http://localhost:9900/api/flight_guard
```

### 上传知识文档

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@aiops-docs/flight_search_latency_spike.md"
```

## 在线演示

当前线上演示地址：

- http://agent.cyruszhang.online

## 适用方向

这个项目适合作为以下方向的开源展示项目：

- Java 后端开发
- AI Agent 编排与落地
- RAG 系统设计
- 故障响应与巡检自动化
- 低成本 LLM 运维工具部署

## 许可证

MIT