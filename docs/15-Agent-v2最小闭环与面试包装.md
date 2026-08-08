# AeroFlow Sentinel Agent v2 Graph Runtime

## 目标

当前线上版本是 v1：使用 ReactAgent、Supervisor-Planner-Executor、Mock 告警、Mock 日志、本地知识库 fallback 和 SSE。

v2 不直接替换线上版本，而是在保留旧接口的前提下，增加一条显式 Graph-driven Evidence-first Agent Runtime：

```text
Intake -> ContextPack -> EvidenceFanout
                       -> Metrics / Logs / Knowledge
       -> Normalize -> Hypothesis -> Bounded Verification Loop
       -> Policy Gate -> Report Artifact -> SSE Trace / Run Replay
```

## 已实现的最小闭环

接口：

```text
POST /api/v2/flight_guard_stream
GET  /api/v2/runs/{runId}
```

运行结果由以下结构组成：

- `AgentRunEvent`：阶段、状态、消息和时间。
- `Evidence`：来源、状态、耗时和工具摘要。
- `AgentRunState`：Graph 节点之间唯一的状态交换边界。
- `ContextPack`：当前节点需要的事件、实体、证据和预算上下文。
- `RiskHypothesis`：带证据引用和验证状态的风险假设。
- `RiskFinding`：风险级别、影响链路、证据引用和建议。
- `AgentRunResult`：一次运行的完整结构化结果、Graph Trace 与 Markdown 投影。

Runtime 使用最多 2 轮的 Verification Loop：检查指标、日志和知识库证据覆盖率，缺口只允许补充一次只读日志查询，最终将结论标记为 `VERIFIED` 或 `NEEDS_REVIEW`。这一步把原来只能通过 Prompt 传递的过程，收敛成可观测、可回放、可测试的状态边界。

## 与前沿技术的衔接

Spring AI 当前稳定线提供模型抽象、结构化输出、Tool Calling、Advisors、Vector Store 和 MCP 能力；Spring AI Alibaba 近期版本提供 Subagent、Routing、Handoff、Workflow、AgentScope 集成等模式。

后续可以把当前实现替换为：

1. Checkpoint Store：将内存 Run Store 替换为 Redis 或数据库持久化节点状态。
2. Structured Model Synthesis：在规则证据筛选后接入 Schema 约束的模型归因，并保留 fallback。
3. MCP Tool Gateway：将指标、日志和知识库统一暴露为带权限的工具适配层。
4. A2A Adapter：把 Metrics Agent、Log Agent 和 Knowledge Agent 变成可远程协作 Agent。
5. OpenTelemetry：记录模型调用、工具调用、检索、Token 和首 Chunk 延迟。

## 面试表述边界

推荐表述：

> AeroFlow Sentinel 已完成一套 Graph-driven Evidence-first Agent Runtime，在保留原 Supervisor-Planner-Executor 线上链路的同时，通过显式 State、Node、Edge 编排指标、日志和知识库证据采集，在节点内部使用有上限 Verification Loop，并通过 SSE 输出带证据引用、Policy Gate 状态和可回放 Graph Trace 的结构化风险报告。

不要表述为“线上已经部署完整 A2A 集群、混合检索评测平台或真实 CLS”，除非对应能力已经有代码、配置和运行证据。

## 后续验收

- 旧版 `/api/flight_guard` 行为不变。
- 新版接口可以在 demo profile 下生成三类证据、验证轮次和结构化报告。
- SSE 客户端可以看到 run、node、evidence、hypothesis、verification、policy、report、completed 事件。
- `GET /api/v2/runs/{runId}` 可以回放已完成运行。
- 构建产物可以正常打包。
- 服务器已在 Demo profile 下运行 v2 公网演示；v1 兼容接口仍保留，当前不宣称接入生产流量或真实 CLS 数据。
