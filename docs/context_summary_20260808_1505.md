# AeroFlow Sentinel Agent 当前上下文摘要

## 目标

将现有 Java 航旅 AIOps 项目升级为可解释、可回放、可演示的 Graph-driven Evidence-first Agent Harness，同时保持 v1 接口和线上部署不变，服务于 Java Agent 工程师面试展示。

## 已完成

- 增加显式 Graph Runtime：Intake、Context Pack、Evidence Fanout、Normalizer、Hypothesis、Verification Loop、Policy Gate、Report Projector。
- 增加 `AgentRunState`、`ContextPack`、`RiskHypothesis`、统一 `AgentRunEvent` 和 `AgentRunResult`。
- 增加最多 2 轮的有界验证循环；证据不足时只允许补充只读日志查询；无证据结论降级为待确认。
- 增加指标、日志、知识库并行取证，以及工具失败隔离。
- 增加结构化风险合成服务；默认 Demo 模式使用规则兜底，模型调用或 Schema 校验失败时回退。
- 增加 v2 SSE Run API 和运行结果查询 API，保留 v1 `/api/chat`、`/api/chat_stream`、`/api/flight_guard`。
- 前端增加 Agent Runtime 控制台：Graph 时间线、三类证据、验证轮次、Evidence ID、风险报告、Run 查询和回放。
- README、公开架构文档、10 条航旅故障 JSONL 样例和本地忽略的面试笔记已补充。

## 已验证

- `mvn test -DskipTests=false`：2 个测试通过。
- `mvn package -DskipTests`：构建成功。
- `node --check src/main/resources/static/app.js`：通过。
- `git diff --check`：无空白错误。
- 未部署服务器，线上继续保持 v1。

## 待处理与残留风险

- 尚未进行真实 HTTP/SSE 浏览器冒烟测试；此前启动后台 Spring 进程受到本机执行策略限制，不能绕过该限制。
- 当前默认关闭真实模型合成，需在 Demo 环境显式开启配置并提供有效 Key 才会走模型路径。
- Run Store 为进程内有限缓存，定位是本地 Demo/架构展示，不宣称生产级持久化。
- 需要最后检查 SSE 完成事件、证据 ID 校验、未使用字段和工作区状态，然后重新跑构建校验。
