package io.github.zhulongqihan.aeroflow.sentinel.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Resource budgets are independent of the optional model and monitoring providers. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.execution")
public class AgentExecutionProperties {
    private int runThreads = 2;
    private int runQueueCapacity = 8;
    private int toolThreads = 4;
    private int toolQueueCapacity = 12;
    private int legacyThreads = 2;
    private int legacyQueueCapacity = 8;
    private int perSessionConcurrency = 1;
    private long toolTimeoutMs = 10_000;
    private long runTimeoutMs = 30_000;
    private long legacyTimeoutMs = 180_000;
}
