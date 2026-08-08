package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRunRequestTest {

    @Test
    void normalizesEmptyFieldsToSafeFlightDefaults() {
        AgentRunRequest request = new AgentRunRequest("", " ", null, "P2").normalized();

        assertEquals("flight-search", request.scenario());
        assertEquals("SHA-PEK", request.route());
        assertEquals("15m", request.timeRange());
        assertEquals("P2", request.severityHint());
    }
}
