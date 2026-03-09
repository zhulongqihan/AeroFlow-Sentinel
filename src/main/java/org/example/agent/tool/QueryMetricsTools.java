package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Prometheus 风险事件查询工具
 * 用于查询航旅预订链路的风险事件信息
 */
@Component
public class QueryMetricsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryMetricsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_PROMETHEUS_ALERTS = "queryPrometheusAlerts";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;
    
    @Value("${prometheus.timeout:10}")
    private int timeout;
    
    @Value("${prometheus.mock-enabled:false}")
    private boolean mockEnabled;
    
    private OkHttpClient httpClient;
    
    @jakarta.annotation.PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();
        logger.info("✅ QueryMetricsTools 初始化成功, Prometheus URL: {}, Mock模式: {}", prometheusBaseUrl, mockEnabled);
    }
    
        /**
         * 查询 Prometheus 活动风险事件
         * 该工具从 Prometheus 告警系统检索当前航旅预订链路的风险事件，包括标签、注释、状态和值
         */
        @Tool(description = "Query active flight booking stability alerts from Prometheus. " +
            "Use this tool to inspect active risks in flight search, booking creation, ticketing, refund/rebooking and GDS gateway chains. " +
            "It returns active/firing alerts with labels, annotations, state and timestamps.")
    public String queryPrometheusAlerts() {
        logger.info("开始查询 Prometheus 活动告警, Mock模式: {}", mockEnabled);
        
        try {
            List<SimplifiedAlert> simplifiedAlerts;
            
            if (mockEnabled) {
                // Mock 模式：返回与文档关联的模拟告警数据
                simplifiedAlerts = buildMockAlerts();
                logger.info("使用 Mock 数据，返回 {} 个模拟告警", simplifiedAlerts.size());
            } else {
                // 真实模式：调用 Prometheus Alerts API
                PrometheusAlertsResult result = fetchPrometheusAlerts();
                
                if (!"success".equals(result.getStatus())) {
                    return buildErrorResponse("Prometheus API 返回非成功状态: " + result.getStatus(), result.getError());
                }
                
                // 转换为简化格式，对于相同的 alertname，只保留第一个
                Set<String> seenAlertNames = new HashSet<>();
                simplifiedAlerts = new ArrayList<>();
                
                for (PrometheusAlert alert : result.getData().getAlerts()) {
                    String alertName = alert.getLabels().get("alertname");
                    
                    // 如果这个 alertname 已经存在，跳过
                    if (seenAlertNames.contains(alertName)) {
                        continue;
                    }
                    
                    // 标记为已见过
                    seenAlertNames.add(alertName);
                    
                    SimplifiedAlert simplified = new SimplifiedAlert();
                    simplified.setAlertName(alertName);
                    simplified.setDescription(alert.getAnnotations().getOrDefault("description", ""));
                    simplified.setState(alert.getState());
                    simplified.setActiveAt(alert.getActiveAt());
                    simplified.setDuration(calculateDuration(alert.getActiveAt()));
                    
                    simplifiedAlerts.add(simplified);
                }
            }
            
            // 构建成功响应
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(true);
            output.setAlerts(simplifiedAlerts);
            output.setMessage(String.format("成功检索到 %d 个活动告警", simplifiedAlerts.size()));
            
            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("Prometheus 告警查询完成: 找到 {} 个告警", simplifiedAlerts.size());
            
            return jsonResult;
            
        } catch (Exception e) {
            logger.error("查询 Prometheus 告警失败", e);
            return buildErrorResponse("查询失败", e.getMessage());
        }
    }
    
    /**
         * 构建 Mock 风险事件数据
         * 与航旅预订链路知识库中的主题对应。
     */
    private List<SimplifiedAlert> buildMockAlerts() {
        List<SimplifiedAlert> alerts = new ArrayList<>();
        Instant now = Instant.now();
        
        SimplifiedAlert searchAlert = new SimplifiedAlert();
        searchAlert.setAlertName("FlightSearchLatencySpike");
        searchAlert.setDescription("航班搜索报价链路 P99 延迟持续 15 分钟高于 6.2 秒，search-aggregator 与 pricing-engine 调用 GDS 网关超时率升至 12.3%，用户搜索结果返回明显变慢。");
        searchAlert.setState("firing");
        Instant searchActiveAt = now.minus(15, ChronoUnit.MINUTES);
        searchAlert.setActiveAt(searchActiveAt.toString());
        searchAlert.setDuration(calculateDuration(searchActiveAt.toString()));
        alerts.add(searchAlert);

        SimplifiedAlert ticketingAlert = new SimplifiedAlert();
        ticketingAlert.setAlertName("TicketingFailureRateRise");
        ticketingAlert.setDescription("出票失败率在近 10 分钟从 1.2% 上升到 8.7%，ticketing-service 与 bsp-gateway 出现超时与重试堆积，存在已扣款未出票和人工补单风险。");
        ticketingAlert.setState("firing");
        Instant ticketingActiveAt = now.minus(10, ChronoUnit.MINUTES);
        ticketingAlert.setActiveAt(ticketingActiveAt.toString());
        ticketingAlert.setDuration(calculateDuration(ticketingActiveAt.toString()));
        alerts.add(ticketingAlert);

        SimplifiedAlert refundAlert = new SimplifiedAlert();
        refundAlert.setAlertName("RefundProcessingDelay");
        refundAlert.setDescription("退改签处理队列延迟持续 20 分钟超过阈值，refund-service 的 MQ 积压升高到 15,000 条，退款到账 SLA 存在明显违约风险。");
        refundAlert.setState("firing");
        Instant refundActiveAt = now.minus(20, ChronoUnit.MINUTES);
        refundAlert.setActiveAt(refundActiveAt.toString());
        refundAlert.setDuration(calculateDuration(refundActiveAt.toString()));
        alerts.add(refundAlert);
        
        return alerts;
    }
    
    /**
     * 从 Prometheus API 获取告警数据
     */
    private PrometheusAlertsResult fetchPrometheusAlerts() throws Exception {
        String apiUrl = prometheusBaseUrl + "/api/v1/alerts";
        logger.debug("请求 Prometheus API: {}", apiUrl);
        
        Request request = new Request.Builder()
                .url(apiUrl)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP 请求失败: " + response.code());
            }
            
            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, PrometheusAlertsResult.class);
        }
    }
    
    /**
     * 计算从 activeAt 到现在的持续时间
     */
    private String calculateDuration(String activeAtStr) {
        try {
            Instant activeAt = Instant.parse(activeAtStr);
            Duration duration = Duration.between(activeAt, Instant.now());
            
            long hours = duration.toHours();
            long minutes = duration.toMinutes() % 60;
            long seconds = duration.getSeconds() % 60;
            
            if (hours > 0) {
                return String.format("%dh%dm%ds", hours, minutes, seconds);
            } else if (minutes > 0) {
                return String.format("%dm%ds", minutes, seconds);
            } else {
                return String.format("%ds", seconds);
            }
        } catch (Exception e) {
            logger.warn("解析时间失败: {}", activeAtStr, e);
            return "unknown";
        }
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message, String error) {
        try {
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            output.setError(error);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\",\"error\":\"%s\"}", message, error);
        }
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * Prometheus 告警信息结构
     */
    @Data
    public static class PrometheusAlert {
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String state;
        private String activeAt;
        private String value;
    }
    
    /**
     * Prometheus 告警查询结果
     */
    @Data
    public static class PrometheusAlertsResult {
        private String status;
        private AlertsData data;
        private String error;
        private String errorType;
    }
    
    @Data
    public static class AlertsData {
        private List<PrometheusAlert> alerts = new ArrayList<>();
    }
    
    /**
     * 简化的告警信息
     */
    @Data
    public static class SimplifiedAlert {
        @JsonProperty("alert_name")
        private String alertName;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("state")
        private String state;
        
        @JsonProperty("active_at")
        private String activeAt;
        
        @JsonProperty("duration")
        private String duration;
    }
    
    /**
     * 告警查询输出
     */
    @Data
    public static class PrometheusAlertsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("alerts")
        private List<SimplifiedAlert> alerts;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("error")
        private String error;
    }
}
