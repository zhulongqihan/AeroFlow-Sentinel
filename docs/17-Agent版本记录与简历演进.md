# AeroFlow Sentinel Agent 版本记录与简历演进

本文档是 Agent 项目的版本事实、验收结果和简历表述的唯一公开记录入口。每次功能、架构、前端或验证方式发生变化，都在文档末尾追加一个版本条目，并同步更新“当前推荐简历版本”。

## 记录规则

- 先记录实际代码和验证结果，再更新简历表述。
- 简历只写已经有源码、配置或可复现 Demo 证据的能力。
- Mock、PoC、规划中的能力必须在面试材料中保留边界，不写成线上生产事实。
- v1 线上接口和服务器部署状态单独记录，不能因 v2 本地演示而混淆。
- 每个版本至少包含：变更目标、实际改动、验证结果、简历变化、未完成事项。

## 当前推荐简历版本

### 项目名称

**AeroFlow Sentinel：面向航旅预订链路的 Graph-driven Evidence-first AIOps Agent Harness**

### 推荐两条简历表述

- 基于 Java 17、Spring Boot、Spring AI Alibaba 构建航旅 AIOps Agent Runtime，用显式 Graph 编排指标、日志、知识库证据采集，并通过 Context Pack 控制节点上下文。
- 引入最多 2 轮 Bounded Verification Loop、Evidence ID 校验和 Policy Gate，借助 SSE 输出可回放 Trace 与结构化风险报告，并用 Trace-driven Evaluation 检查事件顺序、证据覆盖和运行预算。

### 面试强化版

> 我没有把 Agent 做成直接返回长文本的黑盒，而是实现了一个 Graph-driven Agent Harness：Graph 负责确定性控制流，Context Pack 控制节点可见上下文，Verification Loop 负责有限次自校验，Evidence 和 RiskFinding 保存可追溯依据，Policy Gate 限制自动处置边界，SSE 输出完整运行轨迹，Run Store 支持完成态回放。

### 当前不要写入简历的内容

- 不写成已经部署完整 A2A 集群、生产级 Checkpoint、真实 CLS 全链路或线上 v2。
- 不写未经评测脚本证明的“召回率 90%+”“准确率提升”等数字。
- 不把 Demo profile 的 Mock Prometheus、Mock CLS 和本地知识库结果描述为线上真实故障数据。
- 不把 MCP 写成项目核心创新；当前核心是 Graph Runtime、Evidence、Loop、Policy 和 Replay。

## 版本记录

### v2.0.0 - Graph Runtime 最小闭环

日期：2026-08-08

变更目标：将原有 Agent 流程升级为可解释、可观测、可回放的 Java Graph-driven Runtime，同时不破坏 v1。

实际改动：

- 新增 `AgentRunState`、`ContextPack`、`RiskHypothesis`、`AgentRunEvent` 和 `AgentRunResult`。
- 新增 `AgentGraph`、`AgentNode`、显式节点顺序和 Graph Edge。
- 落地 Intake、Context Pack、Evidence Fanout、Evidence Normalizer、Hypothesis Generator、Verification Loop、Policy Gate、Report Projector。
- 指标、日志、知识库并行取证；单个工具失败不会阻断整体报告。
- Verification Loop 最多执行 2 轮，只允许补充只读日志查询；结论必须引用证据。
- 新增 `POST /api/v2/flight_guard_stream` 和 `GET /api/v2/runs/{runId}`。
- 增加结构化模型合成适配器；模型关闭、无 Key、模型返回非法 JSON 或证据校验失败时使用规则 fallback。
- 前端增加 Agent Runtime 控制台、Graph Trace、Evidence、Risk Findings、Markdown Report 和 Run Replay。
- 增加 10 条航旅故障 JSONL 样例、公开架构说明和私有面试材料。

验证结果：

- `mvn test`：2 个测试通过。
- `mvn package -DskipTests`：成功。
- `node --check src/main/resources/static/app.js`：通过。
- `git diff --check`：通过。
- v1 `ChatController` 未修改；v2 未部署服务器。

本版本简历变化：新增“Graph-based Agent Orchestration、Bounded Verification Loop、Context Engineering、Evidence-grounded Reasoning、SSE Trace、Run Replay”等工程化关键词，采用本文档顶部的当前推荐版本。

未完成事项：Run Store 仍为进程内有限缓存；真实模型路径需要有效 API Key；尚未接入生产 Checkpoint、A2A、OpenTelemetry 和真实线上 CLS。

### v2.0.1 - 浏览器验收与移动端布局修复

日期：2026-08-08

触发原因：使用真实浏览器检查 Agent Runtime 时，发现 390px 移动视口下固定 240px 侧边栏挤压主内容，造成横向滚动和 Runtime 标题截断。

实际改动：

- 移动端侧边栏收缩为 64px 图标栏。
- 主内容增加 `min-width: 0`，避免 Flex 子项撑开页面。
- 窄屏下 Agent Runtime 输入区保持单列布局。

浏览器验收：

- Demo 地址：`http://127.0.0.1:9900`，使用 `demo` profile。
- 桌面视口：Graph Trace、三类 Evidence、Finding 和 Markdown Report 正常展示。
- 移动视口：`390 x 844`，`document.documentElement.scrollWidth === 390`，无横向溢出。
- 移动端运行 Agent 成功，状态为“运行成功”，Policy Gate 为 `PASSED`，验证轮次为 1，Finding 数量为 1。
- SSE 运行轨迹包含 28 条事件；点击“回放 Run”后 Run ID、状态、轨迹和报告保持一致。
- 浏览器控制台无运行时错误，仅有正常的 Markdown 初始化日志。
- 截图产物：`output/playwright/agent-runtime-desktop.png`、`output/playwright/agent-runtime-mobile-fixed.png`。

本版本简历变化：核心 Agent 技术表述不变；可增加一句“构建桌面/移动端 Agent Runtime 控制台，支持 SSE 轨迹、证据面板和 Run 回放”，但不把 UI 适配写成独立核心创新。

未完成事项：尚未做多场景批量评测、客户端断线重连压力测试和真实模型 Schema 路径的在线验证。

### v2.0.2 - 服务器部署与真实域名验收

日期：2026-08-08

变更目标：将已验证的 v2 JAR 部署到服务器现有 Agent 入口，并通过 `https://agent.cyruszhang.online/` 验证真实公网链路。

服务器链路：

```text
agent.cyruszhang.online
  -> Docker myblog-nginx:80
  -> host.docker.internal:9900
  -> aeroflow-sentinel-1.0-SNAPSHOT.jar
```

实际改动：

- 服务器原有 9900 端口运行的是 2026-03-09 旧 JAR，`/api/v2` 返回 404，公网页面没有 Runtime 控制台。
- 备份旧 JAR 和部署前 v2 JAR 后，上传当前构建产物并启动 `--spring.profiles.active=demo`。
- 发现原远端部署脚本在没有匹配旧进程时因 `set -euo pipefail` 提前退出；本次使用明确 PID 完成重启，未修改 Nginx 和博客容器。
- 发现浏览器缓存导致新 HTML 与旧 CSS/JS 不同步，为 `styles.css` 和 `app.js` 增加版本查询参数。

公网验收：

- `https://agent.cyruszhang.online/` 返回 200，并展示 Agent Runtime 控制台。
- 真实公网 `POST /api/v2/flight_guard_stream` 返回完整 SSE：Graph、Context Pack、Evidence Fanout、Verification、Policy Gate、Report 和 Completed。
- 浏览器真实域名运行成功：3 个 Evidence 来源、1 条 Risk Finding、28 条 Graph Trace，Policy Gate 为 `PASSED`，验证轮次为 1。
- 点击“回放 Run”后仍为运行成功，Run ID、3 个 Evidence、1 条 Finding 和 28 条 Trace 保持一致。
- 浏览器控制台无运行时错误。

本版本简历变化：可以明确写“已完成服务器 Demo 部署和公网 SSE 演示”，但仍不能写成生产流量、真实 CLS 或线上 v2 生产切换；服务器当前使用 Demo profile 和 Mock 证据源。

回滚信息：旧 JAR 保留在服务器 `target/` 下的 `*.pre-v2.*` 备份文件中；回滚前需停止当前 Java 进程，再恢复旧 JAR 并启动原进程。

未完成事项：服务器还没有 systemd 守护、健康检查自动拉起、Redis Checkpoint 和真实生产数据源；远端部署脚本需要后续修复为健壮的 PID 管理流程。

### v2.0.3 - Trace-driven Evaluation

日期：2026-08-08

变更目标：让 Agent Run 不只“能运行和回放”，还可以按固定契约自动判断一次运行是否合格。

实际改动：

- 新增 `AgentTraceEvaluator` 和 `AgentRunEvaluation`。
- 检查核心事件顺序、Evidence 覆盖、Verification 最大 2 轮、Policy Gate、报告完整性和运行成功状态。
- 新增 `GET /api/v2/runs/{runId}/evaluation`。
- 前端 Runtime 摘要增加 `Trace Eval` 评分。
- 增加完整 Trace 和失败 Trace 的单元测试。

验证结果：

- `mvn test`：4 个测试通过。
- `node --check src/main/resources/static/app.js`：通过。
- 服务器公网运行结果可通过 Evaluation API 查询。

本版本简历变化：采用短版，不再展开所有技术名词：

> 基于 Java 17 / Spring AI Alibaba 构建航旅 AIOps Agent Runtime，以显式 Graph 编排指标、日志和知识库证据采集；通过最多 2 轮 Verification Loop、Evidence 校验和 Policy Gate 约束风险结论，并使用 SSE 输出可回放 Trace。补充 Trace-driven Evaluation，自动校验事件顺序、证据覆盖和运行预算。

未完成事项：当前评测是运行契约评测，不等同于业务准确率、召回率或生产效果评测。

## 下一版追加模板

复制下面模板追加到本文档末尾，并同步修改“当前推荐简历版本”：

```markdown
### vX.Y.Z - 版本标题

日期：YYYY-MM-DD

变更目标：

实际改动：

- 

验证结果：

- 

本版本简历变化：

> 

不可声称：

- 

未完成事项：

- 
```
