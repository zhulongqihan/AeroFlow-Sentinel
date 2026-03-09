# AIOps 多 Agent 编排机制

## 文档目标

这份文档解释项目里最有技术含量的一部分：Supervisor、Planner、Executor 三层 Agent 如何形成巡检闭环。读完后应该能回答：

1. 为什么这里不用单 Agent，而要做多 Agent 分工
2. 规划、执行、再规划如何形成闭环
3. 最终 Markdown 报告为什么可以稳定输出
4. 哪些设计点最适合在面试中展开

## 关键文件

1. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/AiOpsService.java
2. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/controller/ChatController.java

## 总体结构

AiOpsService 不是让一个 Agent 完成所有工作，而是拆成三个角色：

1. SupervisorAgent：流程调度者
2. PlannerAgent：任务规划者，同时承担 Replanner 角色
3. ExecutorAgent：证据执行者，只负责执行当前的第一步

结构如下：

```text
SupervisorAgent
  -> PlannerAgent
  -> ExecutorAgent
```

## 三个角色的职责分工

### 1. SupervisorAgent

Supervisor 不直接查数据，它只负责流程控制：

1. 决定当前是否需要继续规划。
2. 决定是否把任务交给 Executor 执行。
3. 决定何时结束流程并输出最终结果。

### 2. PlannerAgent

Planner 负责“想清楚下一步做什么”。它会：

1. 读取当前输入任务。
2. 读取上一轮 executor_feedback。
3. 判断要查哪类风险、哪类证据。
4. 输出结构化步骤或最终报告。

它既是 Planner，也是 Replanner，因为它会根据执行结果不断调整后续步骤。

### 3. ExecutorAgent

Executor 被明确约束为“只执行第一步”。这样做是为了防止角色失控。

它的职责是：

1. 读取 Planner 最新输出。
2. 调用对应工具收集证据。
3. 按 JSON 格式返回状态、摘要、证据和下一步建议。

## 状态如何传递

多 Agent 之间通过 OverAllState 共享状态，两个最关键的状态键是：

1. planner_plan
2. executor_feedback

闭环逻辑可以简化为：

```text
Planner 输出计划
  -> Executor 消费计划并返回证据
  -> Planner 读取反馈并决定继续执行还是结束
```

这就是“规划 -> 执行 -> 再规划”的核心机制。

## 巡检主流程

```text
前端触发 /api/flight_guard
  -> ChatController.aiOps()
  -> 构建低温度、高 token 的 ChatModel
  -> 获取 ToolCallbacks
  -> AiOpsService.executeAiOpsAnalysis()
  -> buildPlannerAgent()
  -> buildExecutorAgent()
  -> buildSupervisorAgent()
  -> supervisorAgent.invoke(taskPrompt)
  -> 返回 OverAllState
  -> extractFinalReport(state)
  -> SSE 分块推送最终 Markdown 报告
```

## Prompt 设计为什么重要

### Planner Prompt

Planner Prompt 同时承担四项任务：

1. 定义角色职责边界。
2. 约束执行阶段的 JSON 输出格式。
3. 规定失败处理规则。
4. 固定最终报告模板。

当前最终报告必须覆盖：

1. 活跃风险事件清单
2. 风险分析
3. 日志和监控证据
4. 根因结论
5. 处置建议和风险评估

### Executor Prompt

Executor Prompt 的核心思想是“诚实执行”。它被要求：

1. 只执行第一步。
2. 记录工具参数和结果。
3. 如实返回失败原因。
4. 用 JSON 结构输出结果，方便 Planner 消费。

### Supervisor Prompt

Supervisor Prompt 负责让流程在 planner、executor 和 finish 之间有序流转，避免任一 Agent 越界扩张。

## 防幻觉与失败控制

这套多 Agent 设计并不是完全相信模型，而是通过 Prompt 约束和状态控制降低幻觉风险。当前规则包括：

1. 严禁编造数据。
2. 同一方向连续 3 次工具调用失败或无结果时必须停止。
3. 无法完成时要在最终报告里明确说明原因。

这让系统更像一个可控的工程流程，而不是自由生成文本。

## 为什么巡检模型温度更低

巡检场景单独使用更保守的模型参数：

1. temperature=0.3
2. maxToken=8000

原因是：

1. 报告类任务更需要稳定性，而不是发散表达。
2. 长报告需要更大的输出空间。

## 关键设计决策

1. 多 Agent 不是为了炫技，而是为了把规划和执行分开。
2. Executor 只执行第一步，是为了保持角色边界清晰。
3. 最终报告由 Planner 统一输出，避免多个角色直接拼文本导致风格失控。

## 当前局限

1. 状态机主要靠 Prompt 约束，程序化校验还不够强。
2. 巡检轨迹没有持久化存档，审计能力有限。
3. 结果更多是 Markdown 文本，缺少结构化报告输出。

## 简历表达建议

### 项目亮点总结

设计并实现 Supervisor-Planner-Executor 多 Agent 巡检框架，支持规划、执行、再规划的闭环协作，并通过状态传递和失败终止规则生成结构化航旅稳定性分析报告。

### 面试常见追问

1. 为什么要用多 Agent，而不是单 Agent
2. planner_plan 和 executor_feedback 分别解决了什么问题
3. 你是如何控制多 Agent 幻觉和流程失控的