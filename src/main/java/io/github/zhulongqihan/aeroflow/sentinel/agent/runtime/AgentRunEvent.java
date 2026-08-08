package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

/**
 * 面向前端、回放和审计链路的 Agent 运行事件。
 */
public record AgentRunEvent(
        String runId,
        String eventType,
        String node,
        String status,
        String message,
        String occurredAt
) {
}
