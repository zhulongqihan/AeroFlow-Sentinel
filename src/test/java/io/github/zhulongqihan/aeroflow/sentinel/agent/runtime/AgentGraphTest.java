package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import io.github.zhulongqihan.aeroflow.sentinel.agent.graph.AgentGraph;
import io.github.zhulongqihan.aeroflow.sentinel.agent.graph.AgentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentGraphTest {

    @Test
    void executesNodesInDeclaredOrderAndRecordsTrace() {
        AgentRunState state = new AgentRunState("run-test", AgentRunRequest.defaults());
        AgentGraph graph = new AgentGraph(
                List.of(named("first"), named("second")),
                List.of(new AgentGraph.GraphEdge("first", "second")));

        graph.execute(state);

        assertEquals("second", state.currentNode());
        assertTrue(state.events().stream().anyMatch(event -> "node.started".equals(event.eventType())
                && "first".equals(event.node())));
        assertTrue(state.events().stream().anyMatch(event -> "graph.completed".equals(event.eventType())));
    }

    private AgentNode named(String id) {
        return new AgentNode() {
            @Override
            public void execute(AgentRunState state) {
                state.emit("test.event", id, "COMPLETED", "test node");
            }

            @Override
            public String id() {
                return id;
            }
        };
    }
}
