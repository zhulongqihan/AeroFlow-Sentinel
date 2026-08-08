# Agent 前沿升级路线与实施计划

## 1. 目标和判断

这个项目的面试价值不在于堆叠最新名词，而在于证明一条完整链路：业务事件进入 Agent Runtime，Runtime 受策略约束地调用工具，工具结果形成可追踪证据，系统最后输出结构化风险结论和可回放轨迹。

当前服务器是 Java 17、约 1.8 GiB 内存的共享 ECS，线上还承载博客 Docker 服务。因此本项目不直接做 Spring Boot 4 或 Spring AI 2 的线上大版本替换。正确的演进方式是保留 v1 稳定演示链路，在同一代码库增加 v2 runtime 边界，先完成最小闭环，再按能力逐步替换内部实现。

## 2. 能力分层

| 能力 | 当前状态 | 面试口径 |
|---|---|---|
| Supervisor-Planner-Executor | v1 已存在并保持线上运行 | 已有基础多 Agent 协同与单步工具约束 |
| Evidence-first Agent Runtime | v2 已完成 Graph Runtime，并在 Demo profile 服务器演示 | 指标、日志、知识库并行采集并统一为结构化运行状态 |
| SSE 事件轨迹 | v2 已完成公网接口 | 前端可按阶段回放 intake、evidence、risk、report、done |
| Trace-driven Evaluation | v2.0.3 已完成 | 自动检查事件顺序、Evidence 覆盖、Verification 预算、Policy Gate 和报告完整性 |
| Spring AI Tool Calling / RAG | v1 已有，Milvus 可选 | 已有工具和本地 Markdown fallback，生产化评测仍需补齐 |
| 结构化模型结论 | 规则 fallback 已完成，模型开关预留 | 让模型只负责受 Schema 约束的风险归因和建议生成 |
| Graph 持久化编排 | 显式内存 Graph 已完成 | 后续用 Redis/数据库替换 Run Store，增加检查点恢复 |
| MCP Tool Gateway | 规划中 | 给工具增加发现、权限、超时、审计和版本边界 |
| A2A 跨 Agent | 规划中 | 将指标、日志、知识检索拆成可远程协作 Agent |
| GenAI Observability | 规划中 | 记录 run、tool、retrieval、model、token 和首 Chunk 延迟 |

## 3. 已完成的最小闭环

```text
POST /api/v2/flight_guard_stream
  -> intake
  -> parallel evidence: Prometheus / CLS / KnowledgeBase
  -> RiskFinding
  -> Markdown report
  -> SSE trace
```

代码边界：

- `AgentRunEvent`：统一描述阶段和状态。
- `Evidence`：保留来源、成功状态、耗时和摘要。
- `RiskFinding`：保留风险级别、影响链路、证据引用和处置建议。
- `AgentRunResult`：汇总一次运行，包含结构化结果和 Markdown 投影。
- `FlightGuardV2Service`：当前是证据优先的确定性编排器，工具调用可替换为 Graph 或 MCP Adapter。
- `FlightGuardV2Controller`：通过 SSE 暴露运行轨迹；旧 `/api/flight_guard` 不变。

这个闭环的关键设计是让“模型输出”不再等于“系统状态”。模型或规则只能提出结论，系统仍然保存证据来源、工具耗时、失败状态和引用关系，便于审计、回放和后续评测。

## 4. 分阶段实施计划

### P0：证据优先 Graph Runtime，当前已完成

验收：

1. Demo profile 下能采集指标、日志、知识库三类证据。
2. 单个工具失败不会阻断其他证据，失败会进入 `Evidence.status=FAILED`。
3. SSE 能按阶段发送事件，最终返回结构化报告。
4. v1 旧接口和线上部署产物不被修改。

### P1：模型化结构化分析与契约评测，部分完成

`AgentRunRequest` 已由前端传入 `scenario`、`route`、`timeRange`、`severityHint`，当前工具仍使用安全的领域查询模板。

已新增受约束的 `RiskSynthesis` 适配器和 `AgentTraceEvaluator`：

1. 先由规则筛选证据窗口和高危信号。
2. 再让模型通过 Structured Output 生成 `RiskFinding[]`。
3. Schema 校验失败时使用规则结论兜底，不把原始模型文本直接渲染为报告。
4. 将模型调用放在 feature flag 后面，默认仍可用 demo profile 离线运行。
5. 后续再记录 model、prompt version、input evidence ids、latency 和 token usage。

当前已完成 Trace Contract 评测：

1. 校验核心 SSE 事件顺序。
2. 校验 Finding 的 Evidence ID 必须指向成功证据。
3. 校验 Verification 不超过 2 轮。
4. 校验 Policy Gate 和 Markdown 报告状态。
5. 通过 `GET /api/v2/runs/{runId}/evaluation` 返回 0-100 分和逐项检查结果。

建议验收指标：结构化输出成功率 99%、无证据引用的结论比例为 0、报告首个 SSE 事件小于 300 ms、单工具失败后整体仍能返回报告。

### P2：Graph + MCP 工具治理

将当前服务拆成可恢复节点：`Intake`、`EvidenceFanout`、`EvidenceNormalize`、`RiskSynthesis`、`PolicyCheck`、`ReportProjector`。节点之间只传递结构化状态，不传递不可审计的长 Prompt。

将 Prometheus、CLS、RAG 封装为 MCP Tool Gateway：

- 工具声明包含输入 Schema、权限、超时、最大返回量和版本。
- 工具调用统一记录 trace id、run id、tool name、status、latency。
- 高风险动作只允许读取证据，写操作进入人工确认或审批节点。
- 长任务使用任务状态和恢复机制，避免 SSE 连接断开导致运行状态丢失。

### P3：A2A 和可观测性

当单体 runtime 的证据边界稳定后，再拆出三个可独立演进的 Agent：`MetricsAgent`、`LogAgent`、`KnowledgeAgent`。Supervisor 通过 A2A 任务协议编排它们，返回统一的 artifact 和引用。

同时接入 OpenTelemetry GenAI 语义约定，至少覆盖：

- `agent.run`：场景、run id、最终状态。
- `gen_ai.tool.call`：工具、参数摘要、结果状态、延迟。
- `gen_ai.retrieval`：query、检索方式、top-k、命中文档。
- `gen_ai.client.operation`：模型、输入输出 token、首 Chunk 延迟和总耗时。

## 5. 技术选型依据

- [Spring AI API 文档](https://docs.spring.io/spring-ai/reference/api/)：模型抽象、ChatClient、Tool Calling、Advisors、Vector Store、MCP。
- [Spring AI Alibaba v1.1.2.2](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.2)：AgentScope、Subagent、Supervisor、Routing、Handoff、Workflow 等方向。
- [Spring Boot v4.0.6](https://github.com/spring-projects/spring-boot/releases/tag/v4.0.6)：作为后续实验分支的升级基线，不作为当前线上强制升级目标。
- [MCP 2026-07-28 更新](https://blog.modelcontextprotocol.io/posts/2026-07-28/)：无状态服务、缓存、路由、认证和任务机制等新方向。
- [A2A Specification](https://github.com/a2aproject/A2A/blob/main/docs/specification.md)：Agent 间发现、任务和 artifact 协作协议。
- [OpenTelemetry GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/)：模型、工具和检索的统一观测字段。

## 6. 面试表达

### 90 秒版本

> 我把航旅稳定性排障拆成两条边界：v1 负责兼容原有 Supervisor-Planner-Executor 和线上演示，v2 负责验证 evidence-first Agent Runtime。一次巡检先并行获取指标、日志、知识库证据，再生成带证据引用的结构化 RiskFinding，最后通过 SSE 输出运行轨迹和 Markdown 报告。这样模型不是直接输出不可审计的长文本，而是被限制在证据和 Schema 之内。下一步会把证据节点替换成可恢复 Graph，把本地工具升级为带权限和审计的 MCP Gateway，再通过 A2A 拆分指标、日志和知识 Agent，并用 OpenTelemetry 记录 token、检索和工具延迟。

### 简历增量描述

> 基于 Java 17 / Spring AI Alibaba 构建 Evidence-first Agent Runtime：用显式 Graph 编排 Prometheus、CLS 与知识库证据采集，通过 Bounded Verification Loop、Evidence 校验和 Policy Gate 约束风险结论，并使用 SSE 输出可回放 Trace；补充 Trace-driven Evaluation 校验事件顺序、证据覆盖和运行预算。

### 必须避免的表述

- 不说“线上已经部署 A2A 集群”，当前没有对应的远程 Agent 服务和运行证据。
- 不说“真实 CLS 已经接通”，当前 demo profile 使用 Mock，真实模式代码仍是预留。
- 不说“混合检索准确率已经评测到 90%”，除非补充数据集、评测脚本和结果文件。
- 不说“Spring AI 2 已完成升级”，当前线上基线仍是 Spring Boot 3.2 和 Spring AI Alibaba RC2。

## 7. 发布边界

- v2 已通过 Demo profile 部署到共享低内存服务器用于公网演示，但不接管生产流量；v1 兼容接口继续保留。
- 后续服务器更新继续使用独立 JAR 备份、健康检查和人工回滚，避免改变博客容器与现有 v1 边界。
- 后续任何大版本升级先建立独立分支和兼容性矩阵，再做本地 demo、容器 smoke test、回滚验证，最后才考虑服务器灰度。
