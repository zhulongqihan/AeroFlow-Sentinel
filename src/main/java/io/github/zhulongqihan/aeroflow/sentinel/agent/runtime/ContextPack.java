package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import java.util.List;

/**
 * Context Engineering 的稳定边界。
 * 节点只读取当前任务需要的上下文，不直接消费完整对话或全部工具原文。
 */
public record ContextPack(
        String scenario,
        String route,
        String timeRange,
        String severityHint,
        String incidentSummary,
        List<String> entityHints,
        List<String> evidenceSummaries,
        List<String> historicalChecks,
        String nodeGoal,
        int budgetChars
) {
}
