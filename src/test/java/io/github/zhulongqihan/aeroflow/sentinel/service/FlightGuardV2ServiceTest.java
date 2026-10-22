package io.github.zhulongqihan.aeroflow.sentinel.service;

import io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation.AgentTraceEvaluator;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunRequest;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunResult;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.InternalDocsTools;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.QueryLogsTools;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.QueryMetricsTools;
import io.github.zhulongqihan.aeroflow.sentinel.config.AgentExecutionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlightGuardV2ServiceTest {
    private final QueryMetricsTools metrics = mock(QueryMetricsTools.class);
    private final InternalDocsTools docs = mock(InternalDocsTools.class);
    private final QueryLogsTools logs = mock(QueryLogsTools.class);
    private final RiskSynthesisService synthesis = mock(RiskSynthesisService.class);
    private final AgentRunStore store = new AgentRunStore();
    private AgentExecutionService execution;

    @AfterEach void close() { if (execution != null) execution.shutdown(); }

    private FlightGuardV2Service service(AgentExecutionProperties properties, QueryLogsTools logTool) {
        when(metrics.queryPrometheusAlerts()).thenReturn("{\"success\":true,\"alerts\":[{\"description\":\"timeout\"}]}");
        when(docs.queryInternalDocs(anyString())).thenReturn("[{\"id\":\"guide\",\"content\":\"timeout runbook\"}]");
        when(logs.queryLogs(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("{\"success\":true,\"logs\":[{\"message\":\"timeout\"}]}");
        when(synthesis.synthesize(any(), anyList(), anyList(), anyList())).thenAnswer(call -> call.getArgument(3));
        execution = new AgentExecutionService(properties);
        return new FlightGuardV2Service(metrics, docs, logTool, store, synthesis, 2, execution);
    }

    @Test
    void demoSuccessRetainsTraceContractAndReplay() throws Exception {
        var service = service(new AgentExecutionProperties(), logs);
        var handle = service.prepareRun(AgentRunRequest.defaults(), event -> { });
        AgentRunResult result = service.executeRun(handle).get(2, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", result.status());
        assertEquals("PASSED", result.policyStatus());
        assertEquals(3, result.evidence().size());
        assertTrue(result.evidence().stream().allMatch(evidence -> evidence.status().equals("SUCCESS")));
        assertEquals(1, result.verificationRounds());
        assertTrue(new AgentTraceEvaluator().evaluate(result).passed());
        assertSame(result, service.findRun(handle.runId()));
    }

    @Test
    void moreThanSixConcurrentRunsCompleteWithoutParentChildPoolStarvation() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setRunThreads(8);
        properties.setRunQueueCapacity(8);
        properties.setToolThreads(3);
        properties.setToolQueueCapacity(48);
        var service = service(properties, logs);
        when(metrics.queryPrometheusAlerts()).thenAnswer(call -> {
            Thread.sleep(20);
            return "{\"success\":true,\"alerts\":[{\"description\":\"timeout\"}]}";
        });
        List<CompletableFuture<AgentRunResult>> runs = new ArrayList<>();
        for (int i = 0; i < 12; i++) runs.add(service.executeRun(service.prepareRun(AgentRunRequest.defaults(), event -> { })));
        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).get(4, TimeUnit.SECONDS);
        assertTrue(runs.stream().map(CompletableFuture::join).allMatch(run -> run.policyStatus().equals("PASSED")));
    }

    @Test
    void businessErrorAndMissingToolNeverPassEvidenceCoverage() throws Exception {
        var service = service(new AgentExecutionProperties(), null);
        when(metrics.queryPrometheusAlerts()).thenReturn("{\"success\":false,\"error\":\"down\"}");
        AgentRunResult result = service.executeRun(service.prepareRun(AgentRunRequest.defaults(), event -> { })).get(2, TimeUnit.SECONDS);
        assertEquals("ERROR", result.evidence().stream().filter(e -> e.id().equals("metrics")).findFirst().orElseThrow().status());
        assertEquals("UNAVAILABLE", result.evidence().stream().filter(e -> e.id().equals("logs")).findFirst().orElseThrow().status());
        assertEquals("REVIEW_REQUIRED", result.policyStatus());
        assertTrue(result.findings().stream().allMatch(f -> f.status().equals("NEEDS_REVIEW")));
    }

    @Test
    void hangingToolTimesOutWithoutLosingOtherEvidence() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setToolTimeoutMs(80);
        var service = service(properties, logs);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(metrics.queryPrometheusAlerts()).thenAnswer(call -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
            return "unreachable";
        });
        var result = service.executeRun(service.prepareRun(AgentRunRequest.defaults(), event -> { })).get(2, TimeUnit.SECONDS);
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals("TIMEOUT", result.evidence().stream().filter(e -> e.id().equals("metrics")).findFirst().orElseThrow().status());
        assertTrue(result.evidence().stream().filter(e -> e.id().equals("knowledge") || e.id().equals("logs"))
                .allMatch(e -> e.status().equals("SUCCESS")));
        assertEquals("REVIEW_REQUIRED", result.policyStatus());
    }

    @Test
    void totalDeadlineWinsOnceAndLateModelCannotStoreSuccessfulReplay() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setRunTimeoutMs(150);
        var service = service(properties, logs);
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch left = new CountDownLatch(1);
        when(synthesis.synthesize(any(), anyList(), anyList(), anyList())).thenAnswer(call -> {
            entered.countDown();
            while (!release.get()) { try { Thread.sleep(5); } catch (InterruptedException ignored) { } }
            left.countDown();
            return call.getArgument(3);
        });
        var handle = service.prepareRun(AgentRunRequest.defaults(), event -> { });
        var result = service.executeRun(handle);
        AtomicInteger completions = new AtomicInteger();
        result.whenComplete((value, failure) -> completions.incrementAndGet());
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            handle.cancel();
            assertNull(service.findRun(handle.runId()));
        } finally { release.set(true); }
        assertTrue(left.await(1, TimeUnit.SECONDS));
        assertEquals(1, completions.get());
        assertNull(service.findRun(handle.runId()));
    }

    @Test
    void saturatedToolQueueProducesExplicitRejectedEvidence() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setToolThreads(1);
        properties.setToolQueueCapacity(1);
        properties.setToolTimeoutMs(100);
        var service = service(properties, logs);
        when(metrics.queryPrometheusAlerts()).thenAnswer(call -> {
            new CountDownLatch(1).await();
            return "late";
        });
        var result = service.executeRun(service.prepareRun(AgentRunRequest.defaults(), event -> { })).get(2, TimeUnit.SECONDS);
        assertEquals("REJECTED", result.evidence().stream().filter(e -> e.id().equals("knowledge")).findFirst().orElseThrow().status());
        assertEquals("REVIEW_REQUIRED", result.policyStatus());
    }
}
