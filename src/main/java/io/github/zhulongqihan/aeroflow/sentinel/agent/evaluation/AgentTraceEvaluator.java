package io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation;

import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunEvent;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunResult;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.Evidence;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.RiskFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Trace-driven Evaluation 的最小实现。
 * 通过可重复的运行契约检查，避免只凭 Markdown 判断 Agent 是否成功。
 */
@Component
public class AgentTraceEvaluator {

    private static final List<String> REQUIRED_EVENT_ORDER = List.of(
            "graph.started",
            "run.accepted",
            "context.ready",
            "evidence.collected",
            "hypothesis.created",
            "verification.iteration",
            "policy.checked",
            "graph.completed",
            "report.ready",
            "run.completed"
    );

    public AgentRunEvaluation evaluate(AgentRunResult result) {
        List<AgentRunEvaluation.Check> checks = new ArrayList<>();
        checks.add(check("run.succeeded", "SUCCEEDED".equals(result.status()),
                "运行状态为 " + result.status()));
        checks.add(check("trace.order", hasRequiredEventOrder(result.events()),
                "核心事件顺序完整，共 " + result.events().size() + " 条事件"));
        checks.add(check("evidence.coverage", hasEvidenceCoverage(result),
                "Finding 的 Evidence ID 均引用成功证据"));
        checks.add(check("verification.budget", result.verificationRounds() >= 1
                        && result.verificationRounds() <= 2,
                "验证轮次为 " + result.verificationRounds() + "/2"));
        checks.add(check("policy.passed", "PASSED".equals(result.policyStatus()),
                "Policy Gate 状态为 " + result.policyStatus()));
        checks.add(check("report.present", result.markdownReport() != null
                        && !result.markdownReport().isBlank(),
                "Markdown 报告已生成"));

        long passedChecks = checks.stream().filter(AgentRunEvaluation.Check::passed).count();
        int score = (int) Math.round(passedChecks * 100.0 / checks.size());
        boolean passed = passedChecks == checks.size();
        String summary = passed
                ? "Trace Contract 通过：" + passedChecks + "/" + checks.size()
                : "Trace Contract 需要复核：" + passedChecks + "/" + checks.size();
        return new AgentRunEvaluation(result.runId(), passed, score, checks, summary);
    }

    private boolean hasRequiredEventOrder(List<AgentRunEvent> events) {
        int cursor = 0;
        for (AgentRunEvent event : events) {
            if (cursor < REQUIRED_EVENT_ORDER.size()
                    && REQUIRED_EVENT_ORDER.get(cursor).equals(event.eventType())) {
                cursor++;
            }
        }
        return cursor == REQUIRED_EVENT_ORDER.size();
    }

    private boolean hasEvidenceCoverage(AgentRunResult result) {
        Set<String> successfulEvidence = result.evidence().stream()
                .filter(item -> "SUCCESS".equalsIgnoreCase(item.status()))
                .map(Evidence::id)
                .collect(HashSet::new, Set::add, Set::addAll);
        return !result.findings().isEmpty()
                && result.findings().stream().allMatch(this::hasEvidenceIds)
                && result.findings().stream()
                .flatMap(finding -> finding.evidenceIds().stream())
                .allMatch(successfulEvidence::contains);
    }

    private boolean hasEvidenceIds(RiskFinding finding) {
        return finding.evidenceIds() != null && !finding.evidenceIds().isEmpty();
    }

    private AgentRunEvaluation.Check check(String id, boolean passed, String detail) {
        return new AgentRunEvaluation.Check(id, passed, detail);
    }
}
