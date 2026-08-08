package io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation;

import java.util.List;

/**
 * 一次已完成 Agent Run 的可解释评测结果。
 * 评测只读取 Run Snapshot，不参与线上执行控制流。
 */
public record AgentRunEvaluation(
        String runId,
        boolean passed,
        int score,
        List<Check> checks,
        String summary
) {
    public record Check(String id, boolean passed, String detail) {
    }
}
