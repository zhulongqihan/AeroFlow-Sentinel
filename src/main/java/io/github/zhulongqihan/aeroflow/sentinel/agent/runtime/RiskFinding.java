package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import java.util.List;

/**
 * 结构化风险结论，避免巡检结果只能依赖一段不可检索的 Markdown。
 */
public record RiskFinding(
        String id,
        String level,
        String chain,
        String summary,
        List<String> evidenceIds,
        double confidence,
        String status,
        String recommendation
) {
}
