package io.github.zhulongqihan.aeroflow.sentinel.service;

import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunResult;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo 级 Run Store，支持运行完成后的回放。
 * 生产环境可替换为 Redis/数据库实现，Controller 和 Runtime 协议保持不变。
 */
@Component
public class AgentRunStore {

    private static final int MAX_RUNS = 50;
    private final Map<String, AgentRunResult> runs = new ConcurrentHashMap<>();

    public void save(AgentRunResult result) {
        runs.put(result.runId(), result);
        if (runs.size() > MAX_RUNS) {
            runs.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getValue().finishedAt()))
                    .limit(runs.size() - MAX_RUNS)
                    .map(Map.Entry::getKey)
                    .forEach(runs::remove);
        }
    }

    public AgentRunResult find(String runId) {
        return Optional.ofNullable(runs.get(runId)).orElse(null);
    }
}
