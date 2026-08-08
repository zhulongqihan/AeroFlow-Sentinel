package io.github.zhulongqihan.aeroflow.sentinel.controller;

import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunEvent;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunRequest;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunResult;
import io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation.AgentRunEvaluation;
import io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation.AgentTraceEvaluator;
import io.github.zhulongqihan.aeroflow.sentinel.service.FlightGuardV2Service;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

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

    public FlightGuardV2Controller(
            FlightGuardV2Service flightGuardV2Service,
            AgentTraceEvaluator agentTraceEvaluator) {
        this.flightGuardV2Service = flightGuardV2Service;
        this.agentTraceEvaluator = agentTraceEvaluator;
    }

    @PostMapping(value = {"/flight_guard", "/flight_guard_stream"}, produces = "text/event-stream;charset=UTF-8")
    public SseEmitter flightGuardV2(@RequestBody(required = false) AgentRunRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        FlightGuardV2Service.RunHandle handle = flightGuardV2Service.prepareRun(
                request == null ? AgentRunRequest.defaults() : request,
                event -> trySend(emitter, event));

        flightGuardV2Service.executeRun(handle)
                .whenComplete((result, error) -> complete(emitter, result, error));
        return emitter;
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

    private void complete(SseEmitter emitter, AgentRunResult result, Throwable error) {
        if (error != null) {
            logger.error("Agent v2 Runtime 失败", error);
            trySend(emitter, new AgentRunEvent("unknown", "run.failed", "runtime", "FAILED",
                    error.getMessage(), null));
            emitter.completeWithError(error);
            return;
        }

        trySend(emitter, new AgentRunEvent(result.runId(), "report.ready", "report-projector", "COMPLETED",
                result.markdownReport(), result.finishedAt()));
        trySend(emitter, new AgentRunEvent(result.runId(), "run.completed", "runtime", "SUCCEEDED",
                "Agent Runtime Run 完成", result.finishedAt()));
        emitter.complete();
    }

    private void trySend(SseEmitter emitter, AgentRunEvent event) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event()
                        .id(event.runId())
                        .name(event.eventType())
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException exception) {
                logger.debug("SSE 客户端已断开: {}", exception.getMessage());
            }
        }
    }
}
