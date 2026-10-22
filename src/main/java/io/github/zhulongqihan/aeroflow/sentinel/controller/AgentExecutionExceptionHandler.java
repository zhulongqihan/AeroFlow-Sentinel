package io.github.zhulongqihan.aeroflow.sentinel.controller;

import io.github.zhulongqihan.aeroflow.sentinel.service.AgentExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {ChatController.class, FlightGuardV2Controller.class})
public class AgentExecutionExceptionHandler {
    @ExceptionHandler(AgentExecutionService.OverloadedException.class)
    public ResponseEntity<Map<String, Object>> overloaded() {
        return ResponseEntity.status(429).header("Retry-After", "2")
                .body(Map.of("code", "AGENT_BUSY", "message", "当前任务较多，请稍后重试", "retryable", true));
    }
}
