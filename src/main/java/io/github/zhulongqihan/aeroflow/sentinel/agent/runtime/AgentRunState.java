package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Graph Runtime 的共享状态。
 *
 * 状态是节点之间唯一的交换边界，Markdown 只是最终展示投影。
 */
public final class AgentRunState {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final String runId;
    private final AgentRunRequest request;
    private final String startedAt;
    private final long startedNanos;
    private final List<AgentRunEvent> events = new ArrayList<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private final List<RiskHypothesis> hypotheses = new ArrayList<>();
    private final List<RiskFinding> findings = new ArrayList<>();
    private ContextPack contextPack;
    private String currentNode = "intake";
    private String status = "RUNNING";
    private int verificationRounds;
    private String policyStatus = "PENDING";
    private String errorMessage;
    private Consumer<AgentRunEvent> eventSink;

    public AgentRunState(String runId, AgentRunRequest request) {
        this.runId = runId;
        this.request = request.normalized();
        this.startedAt = now();
        this.startedNanos = System.nanoTime();
    }

    public String runId() {
        return runId;
    }

    public AgentRunRequest request() {
        return request;
    }

    public String startedAt() {
        return startedAt;
    }

    public String currentNode() {
        return currentNode;
    }

    public void currentNode(String currentNode) {
        this.currentNode = currentNode;
    }

    public String status() {
        return status;
    }

    public void status(String status) {
        this.status = status;
    }

    public int verificationRounds() {
        return verificationRounds;
    }

    public void verificationRounds(int verificationRounds) {
        this.verificationRounds = verificationRounds;
    }

    public String policyStatus() {
        return policyStatus;
    }

    public void policyStatus(String policyStatus) {
        this.policyStatus = policyStatus;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public ContextPack contextPack() {
        return contextPack;
    }

    public void contextPack(ContextPack contextPack) {
        this.contextPack = contextPack;
    }

    public List<AgentRunEvent> events() {
        return events;
    }

    public List<Evidence> evidence() {
        return evidence;
    }

    public List<RiskHypothesis> hypotheses() {
        return hypotheses;
    }

    public List<RiskFinding> findings() {
        return findings;
    }

    public void addEvidence(Evidence item) {
        evidence.add(item);
    }

    public void addHypothesis(RiskHypothesis hypothesis) {
        hypotheses.add(hypothesis);
    }

    public void replaceHypotheses(List<RiskHypothesis> next) {
        hypotheses.clear();
        hypotheses.addAll(next);
    }

    public void addFinding(RiskFinding finding) {
        findings.add(finding);
    }

    public void replaceFindings(List<RiskFinding> next) {
        findings.clear();
        findings.addAll(next);
    }

    public void attachEventSink(Consumer<AgentRunEvent> eventSink) {
        this.eventSink = eventSink;
    }

    public void emit(String eventType, String node, String status, String message) {
        AgentRunEvent event = record(eventType, node, status, message);
        if (eventSink != null) {
            eventSink.accept(event);
        }
    }

    /** 记录最终事件但暂不推送，便于结果快照与 SSE 收尾保持同一顺序。 */
    public AgentRunEvent record(String eventType, String node, String status, String message) {
        AgentRunEvent event = new AgentRunEvent(runId, eventType, node, status, message, now());
        events.add(event);
        return event;
    }

    public String finishedAt() {
        return now();
    }

    public long elapsedMs() {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }
}
