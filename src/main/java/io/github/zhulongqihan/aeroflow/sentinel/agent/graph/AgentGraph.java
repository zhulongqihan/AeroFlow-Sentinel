package io.github.zhulongqihan.aeroflow.sentinel.agent.graph;

import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.AgentRunState;

import java.util.List;

/**
 * 轻量 Java Graph Runtime。
 *
 * 当前使用确定性边执行，后续可将节点实现替换为 Spring AI Alibaba Graph 节点，
 * 不影响 AgentRunState、SSE 和前端回放协议。
 */
public final class AgentGraph {

    private final List<AgentNode> nodes;
    private final List<GraphEdge> edges;

    public AgentGraph(List<AgentNode> nodes, List<GraphEdge> edges) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
    }

    public void execute(AgentRunState state) {
        state.emit("graph.started", "graph", "RUNNING", "显式状态图开始执行，共 " + nodes.size() + " 个节点");
        for (AgentNode node : nodes) {
            state.currentNode(node.id());
            state.emit("node.started", node.id(), "RUNNING", "节点开始执行: " + node.id());
            try {
                node.execute(state);
                state.emit("node.completed", node.id(), "COMPLETED", "节点执行完成: " + node.id());
            } catch (RuntimeException exception) {
                state.status("FAILED");
                state.errorMessage(exception.getMessage());
                state.emit("node.failed", node.id(), "FAILED", "节点执行失败: " + safeMessage(exception));
                throw exception;
            }
        }
        state.emit("graph.completed", "graph", "COMPLETED", "显式状态图执行完成，边数: " + edges.size());
    }

    public List<AgentNode> nodes() {
        return nodes;
    }

    public List<GraphEdge> edges() {
        return edges;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record GraphEdge(String from, String to) {
    }
}
