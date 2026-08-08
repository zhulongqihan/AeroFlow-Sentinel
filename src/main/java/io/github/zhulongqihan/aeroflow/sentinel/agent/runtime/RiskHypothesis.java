package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import java.util.List;

/**
 * 验证循环中的可审计风险假设。
 */
public record RiskHypothesis(
        String id,
        String statement,
        double confidence,
        List<String> evidenceIds,
        String status
) {
}
