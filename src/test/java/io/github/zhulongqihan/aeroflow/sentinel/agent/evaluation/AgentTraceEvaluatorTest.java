package io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation;

import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunEvent;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunRequest;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunResult;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.ContextPack;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.Evidence;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.RiskFinding;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.RiskHypothesis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTraceEvaluatorTest {

    private final AgentTraceEvaluator evaluator = new AgentTraceEvaluator();

    @Test
    void passesCompleteTraceContract() {
        AgentRunEvaluation evaluation = evaluator.evaluate(result(
                "SUCCEEDED",
                List.of("metrics-1", "logs-1", "knowledge-1"),
                List.of("graph.started", "run.accepted", "context.ready", "evidence.collected",
                        "hypothesis.created", "verification.iteration", "policy.checked", "graph.completed",
                        "report.ready", "run.completed"),
                2,
                "PASSED",
                "# report"));

        assertTrue(evaluation.passed());
        assertEquals(100, evaluation.score());
    }

    @Test
    void rejectsMissingEvidenceAndOverBudgetTrace() {
        AgentRunEvaluation evaluation = evaluator.evaluate(result(
                "SUCCEEDED",
                List.of("metrics-1"),
                List.of("graph.started", "run.completed"),
                3,
                "FAILED",
                ""));

        assertFalse(evaluation.passed());
        assertTrue(evaluation.score() < 100);
    }

    private AgentRunResult result(
            String status,
            List<String> evidenceIds,
            List<String> eventTypes,
            int rounds,
            String policyStatus,
            String report) {
        List<Evidence> evidence = evidenceIds.stream()
                .map(id -> new Evidence(id, id, "SUCCESS", 1, "summary"))
                .toList();
        RiskFinding finding = new RiskFinding(
                "GDS_GATEWAY_LATENCY", "HIGH", "search", "gateway latency", evidenceIds,
                0.86, "VERIFIED", "check supplier timeout");
        List<AgentRunEvent> events = eventTypes.stream()
                .map(type -> new AgentRunEvent("run-1", type, "runtime", "COMPLETED", type, "now"))
                .toList();
        return new AgentRunResult(
                "run-1", status, "start", "finish", events, evidence,
                AgentRunRequest.defaults(), (ContextPack) null, List.<RiskHypothesis>of(),
                List.of(finding), report, "runtime", rounds, policyStatus, null);
    }
}
