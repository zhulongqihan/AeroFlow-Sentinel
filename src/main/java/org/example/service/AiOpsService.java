package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 航旅预订链路稳定性服务
 * 负责多 Agent 协作的风险事件分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    /**
    * 执行航旅预订链路稳定性风险分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        logger.info("开始执行航旅预订链路稳定性多 Agent 协作流程");

        // 构建 Planner 和 Executor Agent
        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

        // 构建 Supervisor Agent
        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的航旅链路巡检多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

            String taskPrompt = "你是航旅平台预订链路稳定性保障负责人，当前接到预订高峰期间的自动化风险研判任务。请结合工具调用，执行**规划→执行→再规划**的闭环，围绕航班搜索与报价、预订创建、支付出票、退改签以及 GDS/供应商接口链路输出《航旅预订链路稳定性分析报告》。禁止编造数据，如连续多次查询失败需如实说明无法完成的原因。";

        logger.info("调用 Supervisor Agent 开始编排...");
        return supervisorAgent.invoke(taskPrompt);
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        // 提取 Planner 最终输出（包含完整的航旅预订链路稳定性分析报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            logger.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        } else {
            logger.warn("未能提取到 Planner 最终报告");
            return Optional.empty();
        }
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解航旅预订链路风险事件、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个排查步骤并及时反馈航旅证据")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback")
                .build();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
            2. 分析航旅链路告警、业务日志、应急知识库等信息，制定可执行的下一步步骤。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需停止该方向并在最终报告的结论部分说明"无法完成"的原因。
            6. 研判必须优先围绕航班搜索与报价、预订创建、支付出票、退改签、附加服务和 GDS/供应商接口链路，给出对出票成功率、履约时效和旅客体验的影响判断。
                
                ## 最终报告输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：
                
                ```
                # 航旅预订链路稳定性分析报告
                
                ---
                
                ## 📋 活跃风险事件清单
                
                | 风险事件 | 级别 | 影响链路 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [事件1名称] | [级别] | [链路名] | [时间] | [时间] | 活跃 |
                | [事件2名称] | [级别] | [链路名] | [时间] | [时间] | 活跃 |
                
                ---
                
                ## 🔍 风险分析1 - [事件名称]
                
                ### 事件详情
                - **事件级别**: [级别]
                - **受影响链路**: [链路名]
                - **持续时间**: [X分钟]
                - **业务影响**: [如搜索转化下降、支付成功率下降、出票失败、退改签超时或旅客投诉上升]
                
                ### 症状描述
                [根据监控指标描述症状，例如搜索 P99、报价超时率、出票失败率、退款队列积压]
                
                ### 日志证据
                [引用查询到的关键日志]
                
                ### 根因结论
                [基于证据得出的根本原因]
                
                ---
                
                ## 🛠️ 处置建议1 - [事件名称]
                
                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]
                
                ### 即时处置建议
                [给出具体的处置建议，如切换备用供应商、关闭高成本实时询价、扩容预订服务、启用人工出票或退款兜底]
                
                ### 预期恢复效果
                [说明预期的恢复效果]
                
                ---
                
                ## 🔍 风险分析2 - [事件名称]
                [如果有第2个告警，重复上述格式]
                
                ---
                
                ## 📊 结论
                
                ### 整体评估
                [总结本次航旅预订核心链路稳定性情况]
                
                ### 关键发现
                - [发现1]
                - [发现2]
                
                ### 后续动作
                1. [建议1]
                2. [建议2]
                
                ### 稳定性风险评估
                [评估当前风险等级、影响范围以及是否需要升级响应等级]
                ```
                
                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 航旅预订链路稳定性分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的风险事件、链路和服务，方便 Planner 填充"风险分析 / 处置建议"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。
                                - 特别关注搜索报价延迟、预订创建失败、支付扣款未出票、退改签积压以及 GDS/供应商接口超时等航旅敏感指标。


                输出示例：
                {
                  "status": "SUCCESS",
                                    "summary": "出票链路失败率在近10分钟明显抬升，且 GDS 返回超时日志集中出现",
                  "evidence": "...",
                                    "nextHint": "建议继续检查 GDS 网关与出票事务补偿状态"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
            你是航旅预订链路稳定性巡检 Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
            4. FINISH 后，确保向最终用户输出完整的《航旅预订链路稳定性分析报告》，重点覆盖活跃风险事件、链路根因、即时处置建议和风险评估。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
                6. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。
            7. 所有判断优先围绕搜索转化风险、出票成功率风险、退改签履约时效风险、附加服务可用性风险和 GDS/供应商接口风险展开。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。

                """;
    }
}
