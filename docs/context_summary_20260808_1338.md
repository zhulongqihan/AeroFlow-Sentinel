# 上下文总结

## 当前目标

用户希望把 AeroFlow Sentinel / My-blog 项目继续包装和演进为更具前沿 AI Agent 技术表达的面试项目。最终目标是提高 Agent 技术前沿度并完成一个可解释的最小闭环；不要求立即在生产服务器上完成真实架构替换，但对外表述必须区分“已运行能力”和“架构/PoC 设计”，不能把未实现能力伪装成已上线能力。

## 已确认的代码与运行事实

- 本地项目：`F:\smart oncall agent\SuperBizAgent-release-2026-01-02`
- 当前分支：`main`，工作区此前干净，本地比 `origin/main` 多 3 个提交。
- 技术基线：Java 17、Spring Boot 3.2.0、Spring AI 1.1.0、Spring AI Alibaba 1.1.0.0-RC2、Milvus、Redis、RabbitMQ、原生 HTML/CSS/JS。
- 现有 v1 接口包括 `/api/chat`、`/api/chat_stream`、`/api/flight_guard`，当前 v1 控制器保持不动。
- 本地 `mvn test -DskipTests=false` 在新增代码前通过，但项目当前没有 `src/test` 测试目录。
- 服务器 SSH 只做了只读检查：`root@118.31.221.81`，应用目录为 `/home/root/apps/superbizagent/SuperBizAgent-release-2026-01-02`。
- 线上运行的是与本地相同 SHA256 的 `target/aeroflow-sentinel-1.0-SNAPSHOT.jar`，Java 17、`demo` profile，约 1.8 GiB 内存，未监听 Milvus 19530；博客相关 Docker 服务正常。
- 服务器资源偏紧，且与博客共享机器，因此暂不把 v2 实验模块部署到线上；线上保留 v1。
- 公开接口检查发现 agent 页面可访问；错误探测主要是扫描流量和少量 SSE 客户端断开，没有证据表明扫描成功。

## 已完成的技术调研

已优先查看官方资料，结论如下：

- Spring AI 当前文档同时列出 2.0.0、1.1.8、1.0.9 稳定线，能力覆盖 ChatClient、Tool Calling、Advisors、MCP 等。
- Spring AI Alibaba 当前可用方向为 AgentScope 集成、Subagent、Supervisor、Skills、Routing、Handoffs 和 Workflow；已核实 release `v1.1.2.2`。
- Spring Boot 当前最新观察版本为 `4.0.6`，但不建议为了面试包装直接升级现有线上基线。
- MCP 近期规范方向强调无状态服务、可缓存列表结果、路由和认证加固、任务机制。
- A2A 已提供 Agent 间协作协议；OpenTelemetry 已有 GenAI 语义约定，适合补充 Agent Run、工具调用、模型调用的可观测性。

## 当前已编辑内容

刚加入 v2 最小闭环模块，均为新增边界，旧接口未改：

- `src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/runtime/AgentRunEvent.java`
- `src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/runtime/Evidence.java`
- `src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/runtime/RiskFinding.java`
- `src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/runtime/AgentRunResult.java`
- `src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/service/FlightGuardV2Service.java`
- `src/main/java/io/github/zhulongqihan/aeroflow/sentinel/controller/FlightGuardV2Controller.java`
- `docs/15-Agent-v2最小闭环与面试包装.md`

v2 的当前流程是：接收一次航旅链路巡检请求 -> 并行拉取 Prometheus 告警、日志检索和内部知识库证据 -> 统一形成 Evidence/RiskFinding/AgentRunResult -> 生成结构化 Markdown 风险报告 -> 用 SSE 按阶段推送给前端。当前实现是“证据优先”的确定性 PoC，未来可替换为 Graph 编排、MCP 工具协议、A2A 跨 Agent 和模型路由，但暂未声称这些未来能力已经在线运行。

## 当前未解决问题

- 需要先编译检查新增模块是否与现有工具类构造函数、Spring Bean 生命周期和序列化配置兼容。
- 需要决定是否补充最小单元测试依赖和测试；项目当前无测试基础设施。
- 需要在完成编译后更新架构/面试文档，明确 v1 线上能力与 v2 PoC/设计能力的边界。
- 已识别但本轮尚未修复的风险：文件上传路径校验、默认配置中的 Milvus/API key、全开放 CORS、HTTP 200 携带业务错误、Markdown 未清洗的 `innerHTML`、会话 JSON 全量同步重写、v1 使用无界缓存线程池。
- 不应在共享低内存服务器直接部署 v2；若后续要部署，需先确认资源和回滚方案。

## 下一步执行顺序

1. 检查新增文件、运行 `git diff --check` 和 `mvn test -DskipTests=false`。
2. 修复编译或静态检查问题，补充最低限度的验证。
3. 完善架构和面试表达文档，说明可运行闭环、未来升级路线和诚实口径。
4. 如验证通过，更新计划状态，明确线上未部署 v2，并汇报验证结果和下一阶段建议。
