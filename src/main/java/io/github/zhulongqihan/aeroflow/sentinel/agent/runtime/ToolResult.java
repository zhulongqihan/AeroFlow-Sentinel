package io.github.zhulongqihan.aeroflow.sentinel.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/** Business status is checked before truncating or presenting the tool payload. */
public record ToolResult(String status, String content) {
    public static ToolResult parse(String payload, ObjectMapper mapper) {
        if (payload == null || payload.isBlank()) return new ToolResult("NO_RESULTS", "工具没有返回结果");
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || root.isNull()) return new ToolResult("NO_RESULTS", payload);
            if (root.isArray()) return new ToolResult(root.isEmpty() ? "NO_RESULTS" : "SUCCESS", payload);
            if (!root.isObject()) return new ToolResult("ERROR", "工具结果格式不正确");
            String declared = root.path("status").asText("").toLowerCase(Locale.ROOT);
            if (declared.equals("unavailable")) return new ToolResult("UNAVAILABLE", payload);
            if (declared.equals("timeout")) return new ToolResult("TIMEOUT", payload);
            if (declared.equals("no_results") || declared.equals("empty")) return new ToolResult("NO_RESULTS", payload);
            if (declared.equals("error") || declared.equals("failed")
                    || root.hasNonNull("error") && (!root.path("error").isTextual() || !root.path("error").asText().isBlank())) {
                return new ToolResult("ERROR", payload);
            }
            for (String field : new String[]{"alerts", "logs", "results"}) {
                if (root.has(field) && root.path(field).isArray() && root.path(field).isEmpty()) {
                    return new ToolResult("NO_RESULTS", payload);
                }
            }
            if (root.has("success") && !root.path("success").asBoolean()) return new ToolResult("ERROR", payload);
            if (root.path("success").asBoolean() || declared.equals("success")) return new ToolResult("SUCCESS", payload);
            return new ToolResult("ERROR", "工具结果未提供明确的成功状态");
        } catch (Exception malformed) {
            return new ToolResult("ERROR", "工具返回了无效的 JSON 结果");
        }
    }
}
