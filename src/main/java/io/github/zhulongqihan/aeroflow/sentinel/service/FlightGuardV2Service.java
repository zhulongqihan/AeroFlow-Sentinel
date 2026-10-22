package io.github.zhulongqihan.aeroflow.sentinel.service;

import io.github.zhulongqihan.aeroflow.sentinel.agent.graph.AgentGraph;
import io.github.zhulongqihan.aeroflow.sentinel.agent.graph.AgentNode;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunEvent;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunRequest;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunResult;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunState;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.ContextPack;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.Evidence;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.RiskFinding;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.RiskHypothesis;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.ToolResult;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.InternalDocsTools;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.QueryLogsTools;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.QueryMetricsTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Graph-driven Evidence-first Agent Runtime。
 *
 * v1 的 Supervisor-Planner-Executor 链路保持不变；v2 使用显式 State、Node、Edge，
 * 将证据采集、验证循环、策略门和报告投影收敛为可回放运行状态。
 */
@Service
public class FlightGuardV2Service {

    private static final Logger logger = LoggerFactory.getLogger(FlightGuardV2Service.class);
    private static final int DEFAULT_MAX_VERIFICATION_ROUNDS = 2;

    private final QueryMetricsTools queryMetricsTools;
    private final InternalDocsTools internalDocsTools;
    private final QueryLogsTools queryLogsTools;
    private final AgentRunStore agentRunStore;
    private final RiskSynthesisService riskSynthesisService;
    private final int maxVerificationRounds;
    private final AgentExecutionService executionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public FlightGuardV2Service(
            QueryMetricsTools queryMetricsTools,
            InternalDocsTools internalDocsTools,
            @Autowired(required = false) QueryLogsTools queryLogsTools,
            AgentRunStore agentRunStore,
            RiskSynthesisService riskSynthesisService,
            @Value("${agent.v2.max-verification-rounds:2}") int maxVerificationRounds,
            AgentExecutionService executionService) {
        this.queryMetricsTools = queryMetricsTools;
        this.internalDocsTools = internalDocsTools;
        this.queryLogsTools = queryLogsTools;
        this.agentRunStore = agentRunStore;
        this.riskSynthesisService = riskSynthesisService;
        this.maxVerificationRounds = Math.max(1, Math.min(maxVerificationRounds, DEFAULT_MAX_VERIFICATION_ROUNDS));
        this.executionService = executionService;
    }

    public RunHandle prepareRun(AgentRunRequest request, Consumer<AgentRunEvent> eventConsumer) {
        AgentRunState state = new AgentRunState(UUID.randomUUID().toString(),
                request == null ? AgentRunRequest.defaults() : request);
        RunHandle handle = new RunHandle(state, executionService.runTimeoutMs());
        state.attachEventSink(event -> {
            if (!handle.finished.get() && eventConsumer != null) eventConsumer.accept(event);
        });
        return handle;
    }

    public CompletableFuture<AgentRunResult> executeRun(RunHandle handle) {
        return executeRun(handle, "run:" + handle.runId());
    }

    public CompletableFuture<AgentRunResult> executeRun(RunHandle handle, String sessionKey) {
        AgentExecutionService.Task<AgentRunResult> task = executionService.submitRun(sessionKey, () -> execute(handle));
        handle.task = task;
        if (handle.finished.get()) task.cancel();
        return task.completion().handle((result, failure) -> {
            handle.finish();
            if (failure != null) throw new CompletionException(failure);
            // A cancelled worker must never publish a later successful snapshot.
            agentRunStore.save(result);
            return result;
        });
    }

    public AgentRunResult findRun(String runId) {
        return agentRunStore.find(runId);
    }

    private AgentRunResult execute(RunHandle handle) {
        AgentRunState state = handle.state();
        try {
            handle.checkActive();
            AgentGraph graph = buildGraph(handle);
            graph.execute(state);
            handle.checkActive();
            state.status("SUCCEEDED");
            String markdownReport = buildMarkdownReport(state);
            state.record("report.ready", "report-projector", "COMPLETED", "结构化报告投影已准备完成");
            state.record("run.completed", "runtime", "SUCCEEDED",
                    "Agent Run 完成，耗时 " + state.elapsedMs() + " ms");
            AgentRunResult result = toResult(state, markdownReport);
            return result;
        } catch (RuntimeException exception) {
            state.status("FAILED");
            state.errorMessage(safeMessage(exception));
            throw exception;
        }
    }

    private AgentGraph buildGraph(RunHandle handle) {
        List<AgentNode> nodes = List.of(
                named("intake", handle, this::intake),
                named("context-pack", handle, this::buildContextPack),
                named("evidence-fanout", handle, state -> collectEvidence(state, handle)),
                named("evidence-normalizer", handle, this::normalizeEvidence),
                named("hypothesis-generator", handle, this::generateHypotheses),
                named("verification-loop", handle, state -> verifyHypotheses(state, handle)),
                named("policy-gate", handle, this::applyPolicyGate),
                named("report-projector", handle, state -> {
                    // 最终报告需要等 Graph 完成后统一投影，避免 SSE 和回放出现重复事件。
                })
        );
        List<AgentGraph.GraphEdge> edges = List.of(
                new AgentGraph.GraphEdge("intake", "context-pack"),
                new AgentGraph.GraphEdge("context-pack", "evidence-fanout"),
                new AgentGraph.GraphEdge("evidence-fanout", "evidence-normalizer"),
                new AgentGraph.GraphEdge("evidence-normalizer", "hypothesis-generator"),
                new AgentGraph.GraphEdge("hypothesis-generator", "verification-loop"),
                new AgentGraph.GraphEdge("verification-loop", "policy-gate"),
                new AgentGraph.GraphEdge("policy-gate", "report-projector")
        );
        return new AgentGraph(nodes, edges);
    }

    private AgentNode named(String id, RunHandle handle, Consumer<AgentRunState> action) {
        return new AgentNode() {
            @Override
            public void execute(AgentRunState state) {
                handle.checkActive();
                action.accept(state);
                handle.checkActive();
            }

            @Override
            public String id() {
                return id;
            }
        };
    }

    private void intake(AgentRunState state) {
        state.emit("run.accepted", "intake", "ACCEPTED",
                "已接收 " + state.request().scenario() + " / " + state.request().route() + " 巡检任务");
    }

    private void buildContextPack(AgentRunState state) {
        AgentRunRequest request = state.request();
        state.contextPack(new ContextPack(
                request.scenario(),
                request.route(),
                request.timeRange(),
                request.severityHint(),
                "分析 " + request.route() + " 航旅链路在 " + request.timeRange() + " 内的稳定性风险",
                List.of("route:" + request.route(), "scenario:" + request.scenario(), "severity:" + request.severityHint()),
                List.of(),
                List.of("历史验证尚未加载"),
                "构建证据窗口并识别可验证风险假设",
                6000
        ));
        state.emit("context.ready", "context-pack", "COMPLETED", "Context Pack 已生成，预算 6000 字符");
    }

    private void collectEvidence(AgentRunState state, RunHandle handle) {
        CompletableFuture<Evidence> metricsFuture = collectAsync(handle, "metrics", "Prometheus", queryMetricsTools::queryPrometheusAlerts);
        CompletableFuture<Evidence> logsFuture = collectAsync(handle, "logs", "CLS", this::queryLogs);
        CompletableFuture<Evidence> docsFuture = collectAsync(handle, "knowledge", "KnowledgeBase", () -> internalDocsTools.queryInternalDocs(
                "GDS supplier timeout flight search latency incident response"));
        await(CompletableFuture.allOf(metricsFuture, logsFuture, docsFuture), handle);
        state.addEvidence(metricsFuture.join());
        state.addEvidence(logsFuture.join());
        state.addEvidence(docsFuture.join());
        state.emit("evidence.collected", "evidence-fanout", "COMPLETED",
                "并行采集完成：指标、日志、知识库共 " + state.evidence().size() + " 个来源");
    }

    private void normalizeEvidence(AgentRunState state) {
        List<String> summaries = state.evidence().stream()
                .map(item -> item.id() + "=" + item.status() + ": " + abbreviate(item.content(), 480))
                .toList();
        ContextPack original = state.contextPack();
        state.contextPack(new ContextPack(
                original.scenario(), original.route(), original.timeRange(), original.severityHint(),
                original.incidentSummary(), original.entityHints(), summaries,
                List.of("工具失败项将进入 Policy Gate 待确认分支"),
                "基于规范化证据生成风险假设并进入验证循环", original.budgetChars()));
        state.emit("evidence.normalized", "evidence-normalizer", "COMPLETED",
                "证据已规范化为 Context Pack，可供后续节点引用");
    }

    private void generateHypotheses(AgentRunState state) {
        String evidenceText = state.evidence().stream()
                .filter(item -> "SUCCESS".equals(item.status()))
                .map(Evidence::content)
                .filter(content -> content != null)
                .map(content -> content.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + " " + right);
        boolean timeoutSignal = evidenceText.contains("timeout") || evidenceText.contains("超时");
        double confidence = timeoutSignal ? 0.86 : 0.62;
        state.addHypothesis(new RiskHypothesis(
                "GDS_GATEWAY_LATENCY",
                timeoutSignal
                        ? "航班搜索或 GDS/供应商网关存在超时信号"
                        : "当前证据尚未形成明确高危信号",
                confidence,
                successfulEvidenceIds(state),
                "OPEN"));
        state.emit("hypothesis.created", "hypothesis-generator", "COMPLETED",
                "已生成 " + state.hypotheses().size() + " 条带证据引用的风险假设");
    }

    private void verifyHypotheses(AgentRunState state, RunHandle handle) {
        List<RiskHypothesis> hypotheses = new ArrayList<>(state.hypotheses());
        for (int round = 1; round <= maxVerificationRounds; round++) {
            handle.checkActive();
            state.verificationRounds(round);
            state.emit("verification.iteration", "verification-loop", "RUNNING",
                    "验证轮次 " + round + "/" + maxVerificationRounds);
            boolean covered = hasEvidenceFrom(state, "metrics")
                    && hasEvidenceFrom(state, "logs")
                    && hasEvidenceFrom(state, "knowledge");
            if (!covered && round == 1) {
                state.emit("verification.gap", "verification-loop", "WAITING",
                        "证据覆盖不足，允许补充一次只读日志查询");
                if (queryLogsTools != null && !hasEvidenceFrom(state, "verification-logs")) {
                    state.addEvidence(await(collectAsync(handle, "verification-logs", "CLS-verification", this::queryLogs), handle));
                }
            }
            boolean verified = hasEvidenceFrom(state, "metrics")
                    && hasEvidenceFrom(state, "logs")
                    && hasEvidenceFrom(state, "knowledge");
            String status = verified ? "VERIFIED" : "NEEDS_REVIEW";
            hypotheses = hypotheses.stream()
                    .map(item -> new RiskHypothesis(item.id(), item.statement(),
                            verified ? Math.max(item.confidence(), 0.86) : Math.min(item.confidence(), 0.62),
                            successfulEvidenceIds(state), status))
                    .toList();
            state.replaceHypotheses(hypotheses);
            state.emit("verification.result", "verification-loop", status,
                    verified ? "证据覆盖指标、日志和知识库，风险假设已验证" : "证据覆盖不足，结论标记为待确认");
            if (verified) {
                break;
            }
        }
        List<RiskFinding> fallback = buildFindings(state);
        state.replaceFindings(riskSynthesisService.synthesize(
                state.contextPack(), state.evidence(), state.hypotheses(), fallback));
    }

    private void applyPolicyGate(AgentRunState state) {
        boolean allFindingsHaveEvidence = state.findings().stream()
                .allMatch(finding -> finding.evidenceIds() != null && !finding.evidenceIds().isEmpty());
        boolean hasFailedEvidence = state.evidence().stream().anyMatch(item -> !"SUCCESS".equals(item.status()));
        state.policyStatus(allFindingsHaveEvidence && !hasFailedEvidence ? "PASSED" : "REVIEW_REQUIRED");
        state.emit("policy.checked", "policy-gate", state.policyStatus(),
                "只读工具策略检查完成，写操作未被自动执行");
    }

    private List<RiskFinding> buildFindings(AgentRunState state) {
        return state.hypotheses().stream()
                .map(hypothesis -> {
                    String level = hypothesis.confidence() >= 0.8 ? "HIGH" : "MEDIUM";
                    String status = "VERIFIED".equals(hypothesis.status()) ? "VERIFIED" : "NEEDS_REVIEW";
                    String recommendation = "检查供应商超时分布、备用供应商切换、缓存命中率和搜索链路降级开关";
                    return new RiskFinding(
                            hypothesis.id(), level, "flight-search-and-pricing", hypothesis.statement(),
                            hypothesis.evidenceIds(), hypothesis.confidence(), status, recommendation);
                })
                .toList();
    }

    private AgentRunResult toResult(AgentRunState state, String markdownReport) {
        return new AgentRunResult(
                state.runId(), state.status(), state.startedAt(), state.finishedAt(),
                List.copyOf(state.events()), List.copyOf(state.evidence()), state.request(), state.contextPack(),
                List.copyOf(state.hypotheses()), List.copyOf(state.findings()), markdownReport,
                state.currentNode(), state.verificationRounds(), state.policyStatus(), state.errorMessage());
    }

    private String buildMarkdownReport(AgentRunState state) {
        StringBuilder report = new StringBuilder();
        report.append("# AeroFlow Sentinel Agent Runtime 巡检报告\n\n");
        report.append("- **Run ID**: ").append(state.runId()).append("\n");
        report.append("- **运行模式**: Graph-driven Evidence-first Runtime\n");
        report.append("- **场景**: ").append(state.request().scenario()).append(" / ")
                .append(state.request().route()).append(" / ").append(state.request().timeRange()).append("\n");
        report.append("- **验证轮次**: ").append(state.verificationRounds()).append("\n");
        report.append("- **Policy Gate**: ").append(state.policyStatus()).append("\n\n");
        report.append("## 风险结论\n\n");
        report.append("| 风险 | 级别 | 状态 | 置信度 | 证据 | 处置建议 |\n");
        report.append("|---|---|---|---:|---|---|\n");
        for (RiskFinding finding : state.findings()) {
            report.append("| ").append(finding.id()).append(" | ").append(finding.level())
                    .append(" | ").append(finding.status()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2f", finding.confidence())).append(" | ")
                    .append(String.join(", ", finding.evidenceIds())).append(" | ")
                    .append(finding.recommendation()).append(" |\n");
        }
        report.append("\n## Graph Trace\n\n");
        for (AgentRunEvent event : state.events()) {
            report.append("- `").append(event.eventType()).append("` ")
                    .append(event.node()).append(" / ").append(event.status()).append("：")
                    .append(event.message()).append("\n");
        }
        report.append("\n## Context Pack\n\n");
        report.append("- 事件摘要：").append(state.contextPack().incidentSummary()).append("\n");
        report.append("- 实体：").append(String.join(", ", state.contextPack().entityHints())).append("\n");
        report.append("- 节点目标：").append(state.contextPack().nodeGoal()).append("\n\n");
        report.append("## Evidence\n\n");
        for (Evidence item : state.evidence()) {
            report.append("### ").append(item.id()).append(" / ").append(item.source()).append("\n\n")
                    .append("- 状态：").append(item.status()).append("\n")
                    .append("- 延迟：").append(item.latencyMs()).append(" ms\n")
                    .append("- 摘要：").append(item.content()).append("\n\n");
        }
        report.append("> 该报告由显式 Graph、证据引用和有上限 Verification Loop 生成。\n");
        return report.toString();
    }

    private List<String> successfulEvidenceIds(AgentRunState state) {
        return state.evidence().stream()
                .filter(item -> "SUCCESS".equals(item.status()))
                .map(Evidence::id)
                .toList();
    }

    private boolean hasEvidenceFrom(AgentRunState state, String id) {
        return state.evidence().stream().anyMatch(item -> item.id().equals(id) && "SUCCESS".equals(item.status()));
    }

    private CompletableFuture<Evidence> collectAsync(RunHandle handle, String id, String source, Supplier<String> supplier) {
        long start = System.nanoTime();
        try {
            handle.checkActive();
            AgentExecutionService.Task<ToolResult> task = executionService.submitTool(handle.remainingMs(),
                    () -> ToolResult.parse(supplier.get(), objectMapper));
            handle.children.add(task);
            if (handle.finished.get()) task.cancel();
            return task.completion().handle((result, failure) -> {
                if (failure == null) return new Evidence(id, source, result.status(), elapsedMs(start), abbreviate(result.content(), 1600));
                Throwable cause = unwrap(failure);
                String status = cause instanceof AgentExecutionService.DeadlineExceededException ? "TIMEOUT"
                        : cause instanceof AgentExecutionService.TaskCancelledException ? "CANCELLED" : "ERROR";
                return new Evidence(id, source, status, elapsedMs(start), "工具采集未完成: " + status);
            });
        } catch (AgentExecutionService.OverloadedException busy) {
            return CompletableFuture.completedFuture(new Evidence(id, source, "REJECTED", elapsedMs(start), "工具执行资源繁忙，请稍后重试"));
        }
    }

    private <T> T await(CompletableFuture<T> future, RunHandle handle) {
        try {
            return future.get(handle.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new AgentExecutionService.TaskCancelledException();
        } catch (TimeoutException expired) {
            throw new AgentExecutionService.DeadlineExceededException();
        } catch (ExecutionException failure) {
            throw new CompletionException(unwrap(failure));
        }
    }

    private Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private String queryLogs() {
        if (queryLogsTools == null) {
            return "{\"status\":\"unavailable\",\"message\":\"日志工具未配置\"}";
        }
        return queryLogsTools.queryLogs("ap-guangzhou", "gds-gateway", "timeout OR supplier OR search", 5);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "工具返回为空";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public static final class RunHandle {
        private final AgentRunState state;
        private final long deadlineNanos;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final List<AgentExecutionService.Task<?>> children = new CopyOnWriteArrayList<>();
        private volatile AgentExecutionService.Task<AgentRunResult> task;

        private RunHandle(AgentRunState state, long timeoutMs) {
            this.state = state;
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }

        public String runId() {
            return state.runId();
        }

        private AgentRunState state() {
            return state;
        }

        private long remainingMs() {
            checkActive();
            return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }

        private void checkActive() {
            if (finished.get() || Thread.currentThread().isInterrupted()) throw new AgentExecutionService.TaskCancelledException();
            if (System.nanoTime() >= deadlineNanos) throw new AgentExecutionService.DeadlineExceededException();
        }

        public void cancel() {
            finish();
            if (task != null) task.cancel();
        }

        private void finish() {
            finished.set(true);
            for (AgentExecutionService.Task<?> child : children) child.cancel();
        }
    }
}
