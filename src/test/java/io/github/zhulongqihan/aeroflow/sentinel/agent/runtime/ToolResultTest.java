package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolResultTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void distinguishesBusinessFailuresEmptyAndUnavailableFromSuccess() {
        expect("SUCCESS", "[{\"id\":\"doc-1\",\"content\":\"runbook\"}]");
        expect("SUCCESS", "{\"success\":true,\"logs\":[{\"message\":\"ok\"}]}");
        expect("ERROR", "{\"success\":false,\"error\":\"provider down\"}");
        expect("ERROR", "{\"error\":{\"code\":503}}");
        expect("ERROR", "{\"status\":\"error\",\"message\":\"timeout\"}");
        expect("NO_RESULTS", "[]");
        expect("NO_RESULTS", "{\"status\":\"no_results\"}");
        expect("NO_RESULTS", "{\"success\":false,\"logs\":[],\"total\":0}");
        expect("NO_RESULTS", "{\"success\":true,\"alerts\":[]}");
        expect("UNAVAILABLE", "{\"status\":\"unavailable\",\"success\":false}");
        expect("TIMEOUT", "{\"status\":\"timeout\"}");
        expect("ERROR", "not a JSON result");
        expect("ERROR", "{\"message\":\"unknown response\"}");
        expect("NO_RESULTS", " ");
    }

    private void expect(String expected, String input) {
        assertEquals(expected, ToolResult.parse(input, mapper).status(), input);
    }
}
