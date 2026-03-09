package io.github.zhulongqihan.aeroflow.sentinel.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查询工具
 * 用于查询航旅预订链路稳定性场景下的 CLS（云日志服务）日志信息
 * 支持 Mock 模式，提供与风险事件关联的模拟日志数据
 */
@Component
public class QueryLogsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryLogsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_LOGS = "queryLogs";
    public static final String TOOL_GET_AVAILABLE_LOG_TOPICS = "getAvailableLogTopics";
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${cls.mock-enabled:false}")
    private boolean mockEnabled;
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    
    @jakarta.annotation.PostConstruct
    public void init() {
        logger.info("✅ QueryLogsTools 初始化成功, Mock模式: {}", mockEnabled);
    }
    
    /**
     * 获取可用的日志主题列表
     * 用于查询前先了解有哪些日志主题可供查询
     */
    @Tool(description = "Get all available log topics and their descriptions. " +
            "Call this tool first before querying logs to understand what log topics are available. " +
            "Returns a list of log topics with their names, descriptions, and example queries.")
    public String getAvailableLogTopics() {
        logger.info("获取可用的日志主题列表");
        
        try {
            List<LogTopicInfo> topics = new ArrayList<>();
            
            LogTopicInfo gdsGateway = new LogTopicInfo();
            gdsGateway.setTopicName("gds-gateway");
            gdsGateway.setDescription("GDS/供应商接口网关日志，包含 Amadeus、Sabre、Travelport 的超时、限流、缓存失效和连接池耗尽信息");
            gdsGateway.setExampleQueries(List.of(
                    "gds_timeout",
                    "amadeus error",
                    "http_status:504",
                    "cache miss"
            ));
            gdsGateway.setRelatedAlerts(List.of("FlightSearchLatencySpike", "TicketingFailureRateRise"));
            topics.add(gdsGateway);

            LogTopicInfo bookingTransaction = new LogTopicInfo();
            bookingTransaction.setTopicName("booking-transaction");
            bookingTransaction.setDescription("预订与出票事务日志，包含 PNR 创建、座位锁定、出票请求、票号回写失败和补偿任务重试等信息");
            bookingTransaction.setExampleQueries(List.of(
                    "pnr create failed",
                    "seat lock timeout",
                    "ticketing error",
                    "compensation retry"
            ));
            bookingTransaction.setRelatedAlerts(List.of("FlightSearchLatencySpike", "TicketingFailureRateRise", "RefundProcessingDelay"));
            topics.add(bookingTransaction);

            LogTopicInfo paymentSettlement = new LogTopicInfo();
            paymentSettlement.setTopicName("payment-settlement");
            paymentSettlement.setDescription("支付与清结算日志，包含支付网关超时、BSP 对账异常、多币种汇率换算和退款处理超时等信息");
            paymentSettlement.setExampleQueries(List.of(
                    "payment timeout",
                    "bsp settlement",
                    "refund timeout",
                    "currency conversion"
            ));
            paymentSettlement.setRelatedAlerts(List.of("TicketingFailureRateRise", "RefundProcessingDelay"));
            topics.add(paymentSettlement);

            LogTopicInfo ancillaryService = new LogTopicInfo();
            ancillaryService.setTopicName("ancillary-service");
            ancillaryService.setDescription("附加服务日志，包含行李额度、选座、餐食、保险接口超时以及库存不一致等信息");
            ancillaryService.setExampleQueries(List.of(
                    "baggage quota",
                    "seat selection failed",
                    "meal unavailable",
                    "insurance timeout"
            ));
            ancillaryService.setRelatedAlerts(List.of("RefundProcessingDelay"));
            topics.add(ancillaryService);
            
            // 构建输出
            LogTopicsOutput output = new LogTopicsOutput();
            output.setSuccess(true);
            output.setTopics(topics);
            output.setAvailableRegions(List.of("ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"));
            output.setDefaultRegion("ap-guangzhou");

            output.setMessage(String.format("共有 %d 个可用的日志主题。建议使用默认地域 'ap-guangzhou' 或省略 region 参数", topics.size()));
            
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            
        } catch (Exception e) {
            logger.error("获取日志主题列表失败", e);
            return "{\"success\":false,\"message\":\"获取日志主题列表失败: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * 查询日志
     * 从云日志服务查询指定条件的日志
     * 
     * @param region 地域，如 ap-guangzhou
     * @param logTopic 日志主题，如 system-metrics, application-logs
     * @param query 查询条件，如 level:ERROR OR cpu_usage:>80
     * @param limit 返回的日志条数，默认20条
     */
    // 有效地域列表
    private static final List<String> VALID_REGIONS = List.of(
            "ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"
    );

    private static final String DEFAULT_REGION = "ap-guangzhou";
    
        @Tool(description = "Query logs from Cloud Log Service (CLS). " +
            "Use this tool to investigate GDS gateway, booking and ticketing transactions, payment settlement, refund and ancillary service events. " +
            "IMPORTANT: Before calling this tool, you should call getAvailableLogTopics to understand what log topics are available. " +
            "Available log topics: " +
            "1) 'gds-gateway' - GDS and supplier gateway logs; " +
            "2) 'booking-transaction' - Booking creation and ticketing transaction logs; " +
            "3) 'payment-settlement' - Payment, BSP settlement and refund logs; " +
            "4) 'ancillary-service' - Ancillary service and inventory event logs. " +
            "logTopic (required, one of the above topics or their CLS topicId), " +
            "query (optional, defaults to a curated search if empty), " +
            "limit (optional, default 20, max 100).")
    public String queryLogs(
            @ToolParam(description = "地域，可选值: ap-guangzhou, ap-shanghai, ap-beijing, ap-chengdu。默认 ap-guangzhou") String region,
            @ToolParam(description = "日志主题，如 gds-gateway, booking-transaction, payment-settlement, ancillary-service，也支持 CLS TopicId") String logTopic,
            @ToolParam(description = "查询条件，支持 Lucene 语法，如 gds_timeout、ticketing error、refund timeout；为空时返回该主题近 5 条核心日志") String query,
            @ToolParam(description = "返回日志条数，默认20，最大100") Integer limit) {
        
        int actualLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        
        String safeQuery = query == null ? "" : query;
        

        try {
            List<LogEntry> logEntries;
            
            if (mockEnabled) {
                // Mock 模式：返回与告警关联的模拟日志数据
                logEntries = buildMockLogs(region, logTopic, safeQuery, actualLimit);
                logger.info("使用 Mock 数据，返回 {} 条日志", logEntries.size());
            } else {
                // 真实模式：调用 CLS API（这里预留接口，后续实现）
                return buildErrorResponse("CLS 真实查询尚未实现，请启用 mock 模式进行测试");
            }
            
            // 构建成功响应
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(!logEntries.isEmpty());
            output.setRegion(region);
            output.setLogTopic(logTopic);
            output.setQuery(safeQuery.isBlank() ? "DEFAULT_QUERY" : safeQuery);
            output.setLogs(logEntries);
            output.setTotal(logEntries.size());
            output.setMessage(logEntries.isEmpty() ? "未找到匹配的日志" : String.format("成功查询到 %d 条日志", logEntries.size()));
            
            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("日志查询完成: 找到 {} 条日志", logEntries.size());
            
            return jsonResult;
            
        } catch (Exception e) {
            logger.error("查询日志失败", e);
            return buildErrorResponse("查询失败: " + e.getMessage());
        }
    }

    /**
     * 构建 Mock 日志数据

     * 根据日志主题和查询条件返回与告警关联的模拟数据
     */
    private List<LogEntry> buildMockLogs(String region, String logTopic, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        Instant now = Instant.now();
        
        String safeTopic = logTopic == null ? "gds-gateway" : logTopic.toLowerCase();
        String normalizedQuery = query == null ? "" : query.toLowerCase();
        
        // 根据日志主题和查询条件生成对应的 mock 数据
        switch (safeTopic) {

            case "gds-gateway":
                logs.addAll(buildGdsGatewayLogs(now, normalizedQuery, limit));
                break;
            case "booking-transaction":
                logs.addAll(buildBookingTransactionLogs(now, normalizedQuery, limit));
                break;
            case "payment-settlement":
                logs.addAll(buildPaymentSettlementLogs(now, normalizedQuery, limit));
                break;
            case "ancillary-service":
                logs.addAll(buildAncillaryServiceLogs(now, normalizedQuery, limit));
                break;
            default:
                logs.addAll(buildGenericLogs(now, normalizedQuery, limit));
        }
        
        if (logs.isEmpty()) {
            logs.addAll(buildGenericLogs(now, normalizedQuery, limit));
        }
        
        // 限制返回条数

        if (logs.size() > limit) {
            logs = logs.subList(0, limit);
        }
        
        return logs;
    }
    
    /**
         * 构建 GDS/供应商接口网关日志
     */
        private List<LogEntry> buildGdsGatewayLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        if (query.isBlank() || query.contains("gds") || query.contains("gateway") || query.contains("timeout") || query.contains("cache")) {
            for (int i = 0; i < 5; i++) {
                LogEntry log = new LogEntry();
                log.setTimestamp(FORMATTER.format(now.minus(i * 2, ChronoUnit.MINUTES)));
                log.setLevel(i < 2 ? "ERROR" : "WARN");
                log.setService("gds-gateway");
                log.setInstance("pod-gds-gateway-7c9d4f6a1-r2m8n");
                log.setMessage(String.format("供应商报价接口超时: provider=%s, route=/shopping/air/search, timeout=%dms, cacheHitRate=%d%%", i % 2 == 0 ? "Amadeus" : "Sabre", 3400 + i * 180, 72 - i * 3));
                log.setMetrics(Map.of(
                        "route", "/shopping/air/search",
                        "provider", i % 2 == 0 ? "Amadeus" : "Sabre",
                        "timeout_ms", String.valueOf(3400 + i * 180),
                        "cache_hit_rate", String.valueOf(72 - i * 3),
                        "http_status", i < 2 ? "504" : "502"
                ));
                logs.add(log);
            }
        }
        
        return logs;
    }
    
    /**
         * 构建预订与出票事务日志
     */
        private List<LogEntry> buildBookingTransactionLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        if (query.isBlank() || query.contains("ticket") || query.contains("pnr") || query.contains("booking") || query.contains("seat") || query.contains("retry")) {
            for (int i = 0; i < 5; i++) {
                LogEntry bookingLog = new LogEntry();
                bookingLog.setTimestamp(FORMATTER.format(now.minus(i * 2, ChronoUnit.MINUTES)));
                bookingLog.setLevel(i < 2 ? "ERROR" : "WARN");
                bookingLog.setService(i % 2 == 0 ? "ticketing-service" : "booking-service");
                bookingLog.setInstance(i % 2 == 0 ? "pod-ticketing-service-6b8f9c7d5-x2k4m" : "pod-booking-service-5c7d8e9f1-m3n2p");
                bookingLog.setMessage(String.format("预订事务链路告警: pnrCreateCost=%dms, seatLock=%s, ticketIssueStatus=%s, compensationRetry=%d", 2600 + i * 140, i < 2 ? "timeout" : "success", i < 3 ? "failed" : "retrying", 8 + i));
                bookingLog.setMetrics(Map.of(
                        "pnr_create_ms", String.valueOf(2600 + i * 140),
                        "seat_lock", i < 2 ? "timeout" : "success",
                        "ticket_issue_status", i < 3 ? "failed" : "retrying",
                        "compensation_retry", String.valueOf(8 + i),
                        "ticket_success_rate", String.format("%.1f", 91.3 + i * 1.1)
                ));
                logs.add(bookingLog);
            }
        }
        
        return logs;
    }
    
    /**
         * 构建支付与清结算日志
     */
        private List<LogEntry> buildPaymentSettlementLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        
        LogEntry paymentTimeout = new LogEntry();
        paymentTimeout.setTimestamp(FORMATTER.format(now.minus(3, ChronoUnit.MINUTES)));
        paymentTimeout.setLevel("WARN");
        paymentTimeout.setService("payment-gateway");
        paymentTimeout.setInstance("payment-gateway-01");
        paymentTimeout.setMessage("支付网关超时: orderNo=AT202603070001, channel=alipay, ticketingCallbackPending=true, timeout=2200ms");
        paymentTimeout.setMetrics(Map.of(
                "timeout_ms", "2200",
                "channel", "alipay",
                "callback_pending", "true",
                "order_no", "AT202603070001",
                "payment_status", "SUCCESS_PENDING_TICKETING"
        ));
        logs.add(paymentTimeout);
        
        LogEntry bspSettlement = new LogEntry();
        bspSettlement.setTimestamp(FORMATTER.format(now.minus(6, ChronoUnit.MINUTES)));
        bspSettlement.setLevel("WARN");
        bspSettlement.setService("settlement-service");
        bspSettlement.setInstance("settlement-service-01");
        bspSettlement.setMessage("BSP 对账延迟: batch=20260307-AM, pendingRecords=184, currencyConversionQueue=47, reconciliationWindow=18m");
        bspSettlement.setMetrics(Map.of(
                "batch", "20260307-AM",
                "pending_records", "184",
                "currency_conversion_queue", "47",
                "reconciliation_window_min", "18",
                "warning", "Settlement delayed"
        ));
        logs.add(bspSettlement);
        
        LogEntry refundDelay = new LogEntry();
        refundDelay.setTimestamp(FORMATTER.format(now.minus(8, ChronoUnit.MINUTES)));
        refundDelay.setLevel("ERROR");
        refundDelay.setService("refund-service");
        refundDelay.setInstance("refund-service-02");
        refundDelay.setMessage("退款处理超时: refundRequest=RF202603070245, provider=airline-direct, refundSla=30m, currentWait=46m");
        refundDelay.setMetrics(Map.of(
                "refund_request", "RF202603070245",
                "provider", "airline-direct",
                "refund_sla_min", "30",
                "current_wait_min", "46",
                "warning", "Refund SLA breached"
        ));
        logs.add(refundDelay);
        
        return logs;
    }
    
    /**
         * 构建附加服务日志
     */
        private List<LogEntry> buildAncillaryServiceLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        if (query.isBlank() || query.contains("seat") || query.contains("baggage") || query.contains("meal") || query.contains("insurance")) {
            LogEntry seatEvent = new LogEntry();
            seatEvent.setTimestamp(FORMATTER.format(now.minus(11, ChronoUnit.MINUTES)));
            seatEvent.setLevel("WARN");
            seatEvent.setService("seat-selection-service");
            seatEvent.setInstance("seat-selection-service-01");
            seatEvent.setMessage("选座接口异常: flight=MU5123, cabin=Y, seatMapVersion=20260307-rc2, provider response timeout after 1800ms");
            seatEvent.setMetrics(Map.of(
                    "flight", "MU5123",
                    "cabin", "Y",
                    "seat_map_version", "20260307-rc2",
                    "timeout_ms", "1800",
                    "rollback_ready", "true"
            ));
            logs.add(seatEvent);

            LogEntry baggageEvent = new LogEntry();
            baggageEvent.setTimestamp(FORMATTER.format(now.minus(13, ChronoUnit.MINUTES)));
            baggageEvent.setLevel("ERROR");
            baggageEvent.setService("ancillary-service");
            baggageEvent.setInstance("ancillary-service-03");
            baggageEvent.setMessage("附加服务库存不一致: baggage_quota sync delayed, route=SHA-PEK, mismatchCount=37, fallback=manual-review");
            baggageEvent.setMetrics(Map.of(
                    "service_type", "baggage_quota",
                    "route", "SHA-PEK",
                    "mismatch_count", "37",
                    "fallback", "manual-review",
                    "error_rate", "7.6"
            ));
            logs.add(baggageEvent);
        }
        
        return logs;
    }
    
    /**
     * 构建通用日志
     */
    private List<LogEntry> buildGenericLogs(Instant now, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        
        for (int i = 0; i < Math.min(limit, 10); i++) {
            LogEntry log = new LogEntry();
            log.setTimestamp(FORMATTER.format(now.minus(i, ChronoUnit.MINUTES)));
            log.setLevel(i % 3 == 0 ? "ERROR" : (i % 3 == 1 ? "WARN" : "INFO"));
            log.setService("generic-service");
            log.setInstance("instance-" + i);
            log.setMessage("日志消息 #" + i + ", 查询条件: " + query);
            log.setMetrics(new HashMap<>());
            logs.add(log);
        }
        
        return logs;
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message) {
        try {
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\"}", message);
        }
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * 日志条目
     */
    @Data
    public static class LogEntry {
        @JsonProperty("timestamp")
        private String timestamp;
        
        @JsonProperty("level")
        private String level;
        
        @JsonProperty("service")
        private String service;
        
        @JsonProperty("instance")
        private String instance;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("metrics")
        private Map<String, String> metrics;
    }
    
    /**
     * 日志查询输出
     */
    @Data
    public static class QueryLogsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("region")
        private String region;
        
        @JsonProperty("log_topic")
        private String logTopic;
        
        @JsonProperty("query")
        private String query;
        
        @JsonProperty("logs")
        private List<LogEntry> logs;
        
        @JsonProperty("total")
        private int total;
        
        @JsonProperty("message")
        private String message;
    }
    
    /**
     * 日志主题信息
     */
    @Data
    public static class LogTopicInfo {
        @JsonProperty("topic_name")
        private String topicName;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("example_queries")
        private List<String> exampleQueries;
        
        @JsonProperty("related_alerts")
        private List<String> relatedAlerts;
    }
    
    /**
     * 日志主题列表输出
     */
    @Data
    public static class LogTopicsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("topics")
        private List<LogTopicInfo> topics;
        
        @JsonProperty("available_regions")
        private List<String> availableRegions;
        
        @JsonProperty("default_region")
        private String defaultRegion;
        
        @JsonProperty("message")
        private String message;
    }
}
