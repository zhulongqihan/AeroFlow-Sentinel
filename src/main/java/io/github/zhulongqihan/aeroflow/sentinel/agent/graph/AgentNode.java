package io.github.zhulongqihan.aeroflow.sentinel.agent.graph;

import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunState;

/**
 * Graph 节点契约。节点只通过 AgentRunState 读写数据。
 */
@FunctionalInterface
public interface AgentNode {

    void execute(AgentRunState state);

    default String id() {
        return getClass().getSimpleName();
    }
}
