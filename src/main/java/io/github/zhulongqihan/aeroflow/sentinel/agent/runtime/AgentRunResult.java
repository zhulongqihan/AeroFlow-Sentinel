package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import java.util.List;

/**
 * 一次 Agent 巡检的完整结果。
 * Markdown 是展示投影，结构化字段才是后续回放、评测和 A2A 传输的稳定边界。
 */
public record AgentRunResult(
        String runId,
        String status,
        String startedAt,
        String finishedAt,
        List<AgentRunEvent> events,
        List<Evidence> evidence,
        AgentRunRequest request,
        ContextPack contextPack,
        List<RiskHypothesis> hypotheses,
        List<RiskFinding> findings,
        String markdownReport,
        String currentNode,
        int verificationRounds,
        String policyStatus,
        String errorMessage
) {
}
