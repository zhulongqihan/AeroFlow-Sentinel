package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

/**
 * Agent 研判所使用的单条证据。
 * content 保留工具原始摘要，后续可以替换为带来源、TTL 和脱敏信息的 EvidenceRef。
 */
public record Evidence(
        String id,
        String source,
        String status,
        long latencyMs,
        String content
) {
}
