package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

/**
 * 一次航旅稳定性 Agent Run 的输入。
 */
public record AgentRunRequest(
        String scenario,
        String route,
        String timeRange,
        String severityHint
) {

    public static AgentRunRequest defaults() {
        return new AgentRunRequest("flight-search", "SHA-PEK", "15m", "P1");
    }

    public AgentRunRequest normalized() {
        AgentRunRequest defaults = defaults();
        return new AgentRunRequest(
                valueOrDefault(scenario, defaults.scenario()),
                valueOrDefault(route, defaults.route()),
                valueOrDefault(timeRange, defaults.timeRange()),
                valueOrDefault(severityHint, defaults.severityHint())
        );
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
