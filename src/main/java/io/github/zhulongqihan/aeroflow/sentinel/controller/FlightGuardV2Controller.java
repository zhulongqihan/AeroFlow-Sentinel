package io.github.zhulongqihan.aeroflow.sentinel.controller;

import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunEvent;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunRequest;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunResult;
import io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation.AgentRunEvaluation;
import io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation.AgentTraceEvaluator;
import io.github.zhulongqihan.aeroflow.sentinel.service.FlightGuardV2Service;
import io.github.zhulongqihan.aeroflow.sentinel.service.AgentExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent v2 Runtime 入口。
 * 旧版 /api/flight_guard 保持不变，便于线上回滚和对比演示。
 */
@RestController
@RequestMapping("/api/v2")
public class FlightGuardV2Controller {

    private static final Logger logger = LoggerFactory.getLogger(FlightGuardV2Controller.class);
    private final FlightGuardV2Service flightGuardV2Service;
    private final AgentTraceEvaluator agentTraceEvaluator;
    private final AgentExecutionService executionService;

    public FlightGuardV2Controller(
            FlightGuardV2Service flightGuardV2Service,
            AgentTraceEvaluator agentTraceEvaluator, AgentExecutionService executionService) {
        this.flightGuardV2Service = flightGuardV2Service;
        this.agentTraceEvaluator = agentTraceEvaluator;
        this.executionService = executionService;
    }

    @PostMapping(value = {"/flight_guard", "/flight_guard_stream"}, produces = "text/event-stream;charset=UTF-8")
    public SseEmitter flightGuardV2(@RequestBody(required = false) AgentRunRequest request,
                                  @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                  HttpServletRequest servletRequest) {
        AgentExecutionService.CompletionDelivery delivery = executionService.reserveCompletion();
        try {
            SseEmitter emitter = new SseEmitter(180_000L);
            AtomicReference<FlightGuardV2Service.RunHandle> active = new AtomicReference<>();
            AtomicBoolean completed = new AtomicBoolean();
            FlightGuardV2Service.RunHandle handle = flightGuardV2Service.prepareRun(
                    request == null ? AgentRunRequest.defaults() : request,
                    event -> {
                        if (!completed.get() && !trySend(emitter, event) && active.get() != null) active.get().cancel();
                    });
            active.set(handle);
            emitter.onTimeout(handle::cancel);
            emitter.onError(error -> handle.cancel());
            emitter.onCompletion(() -> {
                if (!completed.get()) handle.cancel();
            });

            String key = sessionId == null || sessionId.isBlank()
                    ? "source:" + servletRequest.getRemoteAddr() : "session:" + sessionId.trim();
            flightGuardV2Service.executeRun(handle, key)
                    .whenComplete((result, error) -> {
                        if (completed.compareAndSet(false, true)) delivery.dispatch(
                                () -> complete(emitter, handle.runId(), result, error));
                    });
            return emitter;
        } catch (RuntimeException failure) {
            delivery.close();
            throw failure;
        }
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<AgentRunResult> getRun(@PathVariable String runId) {
        AgentRunResult result = flightGuardV2Service.findRun(runId);
        return result == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).build()
                : ResponseEntity.ok(result);
    }

    @GetMapping("/runs/{runId}/evaluation")
    public ResponseEntity<AgentRunEvaluation> evaluateRun(@PathVariable String runId) {
        AgentRunResult result = flightGuardV2Service.findRun(runId);
        return result == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).build()
                : ResponseEntity.ok(agentTraceEvaluator.evaluate(result));
    }

    private void complete(SseEmitter emitter, String runId, AgentRunResult result, Throwable error) {
        if (error != null) {
            while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
            String status = error instanceof AgentExecutionService.DeadlineExceededException ? "TIMED_OUT"
                    : error instanceof AgentExecutionService.TaskCancelledException ? "CANCELLED" : "FAILED";
            logger.warn("Agent v2 Runtime 结束: run={}, status={}", runId, status);
            trySend(emitter, new AgentRunEvent(runId, "run.failed", "runtime", status,
                    status.equals("TIMED_OUT") ? "运行超时，请稍后重试" : status.equals("CANCELLED") ? "运行已取消" : "运行失败，请稍后重试", null));
            emitter.complete();
            return;
        }

        trySend(emitter, new AgentRunEvent(result.runId(), "report.ready", "report-projector", "COMPLETED",
                result.markdownReport(), result.finishedAt()));
        trySend(emitter, new AgentRunEvent(result.runId(), "run.completed", "runtime", "SUCCEEDED",
                "Agent Runtime Run 完成", result.finishedAt()));
        emitter.complete();
    }

    private boolean trySend(SseEmitter emitter, AgentRunEvent event) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event()
                        .id(event.runId())
                        .name(event.eventType())
                        .data(event, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException exception) {
                logger.debug("SSE 客户端已断开: {}", exception.getMessage());
                return false;
            }
        }
    }
}
