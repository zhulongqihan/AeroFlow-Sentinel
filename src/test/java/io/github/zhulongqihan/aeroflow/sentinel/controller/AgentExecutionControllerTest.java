package io.github.zhulongqihan.aeroflow.sentinel.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import io.github.zhulongqihan.aeroflow.sentinel.agent.evaluation.AgentTraceEvaluator;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.InternalDocsTools;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.QueryLogsTools;
import io.github.zhulongqihan.aeroflow.sentinel.agent.tool.QueryMetricsTools;
import io.github.zhulongqihan.aeroflow.sentinel.config.AgentExecutionProperties;
import io.github.zhulongqihan.aeroflow.sentinel.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.MediaType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Timeout(15)
class AgentExecutionControllerTest {
    private AgentExecutionService execution;
    private final ChatService chat = mock(ChatService.class);
    private final AiOpsService aiOps = mock(AiOpsService.class);
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private QueryMetricsTools runtimeMetrics;

    @AfterEach void close() { if (execution != null) execution.shutdown(); }

    private MockMvc legacy(AgentExecutionProperties properties) {
        execution = new AgentExecutionService(properties);
        return MockMvcBuilders.standaloneSetup(new ChatController(aiOps, chat, sessions, execution))
                .setControllerAdvice(new AgentExecutionExceptionHandler()).build();
    }

    private MockMvc runtime(AgentExecutionProperties properties, AtomicInteger events) {
        execution = new AgentExecutionService(properties);
        QueryMetricsTools metrics = mock(QueryMetricsTools.class);
        runtimeMetrics = metrics;
        QueryLogsTools logs = mock(QueryLogsTools.class);
        InternalDocsTools docs = mock(InternalDocsTools.class);
        RiskSynthesisService synthesis = mock(RiskSynthesisService.class);
        when(metrics.queryPrometheusAlerts()).thenReturn("{\"success\":true,\"alerts\":[{\"message\":\"timeout\"}]}");
        when(logs.queryLogs(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("{\"success\":true,\"logs\":[{\"message\":\"timeout\"}]}");
        when(docs.queryInternalDocs(anyString())).thenReturn("[{\"id\":\"guide\",\"content\":\"timeout\"}]");
        when(synthesis.synthesize(any(), anyList(), anyList(), anyList())).thenAnswer(call -> call.getArgument(3));
        FlightGuardV2Service service = new FlightGuardV2Service(metrics, docs, logs, new AgentRunStore(), synthesis, 2, execution);
        return MockMvcBuilders.standaloneSetup(new FlightGuardV2Controller(service, new AgentTraceEvaluator(), execution))
                .setControllerAdvice(new AgentExecutionExceptionHandler()).build();
    }

    @Test
    void v1OverloadReturnsRetryable429BeforeCallingModel() throws Exception {
        MockMvc mvc = legacy(new AgentExecutionProperties());
        CountDownLatch entered = new CountDownLatch(1);
        var occupied = execution.submitRun("session:shared", () -> {
            entered.countDown(); new CountDownLatch(1).await(); return null;
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"Id\":\"shared\",\"Question\":\"test\"}"))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"))
                    .andExpect(jsonPath("$.code").value("AGENT_BUSY")).andExpect(jsonPath("$.retryable").value(true));
            verifyNoInteractions(chat);
        } finally { occupied.cancel(); }
    }

    @Test
    void v2UsesSessionHeaderAndReturnsRetryable429BeforeStartingStream() throws Exception {
        MockMvc mvc = runtime(new AgentExecutionProperties(), new AtomicInteger());
        CountDownLatch entered = new CountDownLatch(1);
        var occupied = execution.submitLegacy("session:shared", () -> {
            entered.countDown(); new CountDownLatch(1).await(); return null;
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            mvc.perform(post("/api/v2/flight_guard_stream").header("X-Session-Id", "shared")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"));
        } finally { occupied.cancel(); }
    }

    @Test
    void terminalBacklogRejectsV1StreamBeforeCallingModelAndRecovers() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        MockMvc mvc = legacy(properties);
        java.util.List<AgentExecutionService.CompletionDelivery> occupied = occupyTerminalCapacity(properties);
        try {
            mvc.perform(post("/api/chat_stream").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"Id\":\"chat-1\",\"Question\":\"test\"}"))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"));
            verifyNoInteractions(chat);
        } finally { occupied.forEach(AgentExecutionService.CompletionDelivery::close); }
        try (var recovered = execution.reserveCompletion()) { assertNotNull(recovered); }
    }

    @Test
    void terminalBacklogRejectsV2StreamBeforeCallingTools() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        MockMvc mvc = runtime(properties, new AtomicInteger());
        java.util.List<AgentExecutionService.CompletionDelivery> occupied = occupyTerminalCapacity(properties);
        try {
            mvc.perform(post("/api/v2/flight_guard_stream").header("X-Session-Id", "blocked")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"));
            verifyNoInteractions(runtimeMetrics);
        } finally { occupied.forEach(AgentExecutionService.CompletionDelivery::close); }
    }

    private java.util.List<AgentExecutionService.CompletionDelivery> occupyTerminalCapacity(AgentExecutionProperties properties) {
        java.util.List<AgentExecutionService.CompletionDelivery> deliveries = new java.util.ArrayList<>();
        int capacity = properties.getRunThreads() + properties.getRunQueueCapacity()
                + properties.getLegacyThreads() + properties.getLegacyQueueCapacity();
        for (int i = 0; i < capacity; i++) deliveries.add(execution.reserveCompletion());
        return deliveries;
    }

    @Test
    void v2SseHasExactlyOneReportAndTerminalAndReplayRemainsReadable() throws Exception {
        MockMvc mvc = runtime(new AgentExecutionProperties(), new AtomicInteger());
        MvcResult pending = mvc.perform(post("/api/v2/flight_guard_stream").header("X-Session-Id", "demo")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(3000);
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1, occurrences(body, "event:run.completed"));
        assertEquals(1, occurrences(body, "event:report.ready"));
        assertFalse(body.contains("event:run.failed"));
        String runId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                body.substring(body.indexOf("data:") + 5, body.indexOf("\n\n"))).path("runId").asText();
        mvc.perform(get("/api/v2/runs/" + runId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(get("/api/v2/runs/" + runId + "/evaluation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void v1SyncResponseRetainsAnswerContract() throws Exception {
        MockMvc mvc = legacy(new AgentExecutionProperties());
        prepareChatSession();
        when(chat.executeChat(any(), anyString())).thenReturn("offline answer");
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"chat-1\",\"Question\":\"test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("offline answer"));
        verify(sessions).appendMessagePair(eq("chat-1"), eq("test"), eq("offline answer"), any(Runnable.class));
    }

    @Test
    void v1SyncDeadlineReturns504AndSameSessionCanRetry() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setLegacyTimeoutMs(150);
        MockMvc mvc = legacy(properties);
        prepareChatSession();
        when(chat.executeChat(any(), anyString())).thenAnswer(call -> {
            new CountDownLatch(1).await();
            return "late";
        });
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"chat-1\",\"Question\":\"test\"}"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.data.success").value(false));
        verify(sessions, never()).appendMessagePair(anyString(), anyString(), anyString(), any(Runnable.class));
        doReturn("retry").when(chat).executeChat(any(), anyString());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"chat-1\",\"Question\":\"test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("retry"));
    }

    @Test
    void v2TotalDeadlineProducesExactlyOneTimedOutTerminal() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setRunTimeoutMs(150);
        properties.setToolTimeoutMs(1000);
        MockMvc mvc = runtime(properties, new AtomicInteger());
        CountDownLatch interrupted = new CountDownLatch(1);
        when(runtimeMetrics.queryPrometheusAlerts()).thenAnswer(call -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
            return "late";
        });
        MvcResult pending = mvc.perform(post("/api/v2/flight_guard_stream").header("X-Session-Id", "timeout")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(3000);
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1, occurrences(body, "event:run.failed"));
        assertEquals(1, occurrences(body, "\"status\":\"TIMED_OUT\""));
        assertFalse(body.contains("event:run.completed"));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void v1NeverEndingModelStreamDisposesAndReturnsOneErrorWithinDeadline() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setLegacyTimeoutMs(150);
        MockMvc mvc = legacy(properties);
        ReactAgent agent = prepareChatSession();
        CountDownLatch cancelled = new CountDownLatch(1);
        when(agent.stream(anyString())).thenReturn(Flux.<com.alibaba.cloud.ai.graph.NodeOutput>never().doOnCancel(cancelled::countDown));
        MvcResult pending = mvc.perform(post("/api/chat_stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"chat-1\",\"Question\":\"test\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(3000);
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1, occurrences(body, "\"type\":\"error\""));
        assertFalse(body.contains("\"type\":\"done\""));
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        verify(sessions, never()).appendMessagePair(anyString(), anyString(), anyString(), any(Runnable.class));
    }

    @Test
    void v1StreamRetainsIncrementalContentAndOneDoneMessage() throws Exception {
        MockMvc mvc = legacy(new AgentExecutionProperties());
        ReactAgent agent = prepareChatSession();
        StreamingOutput first = mock(StreamingOutput.class);
        StreamingOutput second = mock(StreamingOutput.class);
        when(first.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(second.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(first.message()).thenReturn(new AssistantMessage("hello "));
        when(second.message()).thenReturn(new AssistantMessage("world"));
        when(agent.stream(anyString())).thenReturn(Flux.just(first, second));
        MvcResult pending = mvc.perform(post("/api/chat_stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"chat-1\",\"Question\":\"test\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(3000);
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(2, occurrences(body, "\"type\":\"content\""));
        assertEquals(1, occurrences(body, "\"type\":\"done\""));
        verify(sessions).appendMessagePair(eq("chat-1"), eq("test"), eq("hello world"), any(Runnable.class));
    }

    @Test
    void traditionalInspectionAliasesRetainContentAndDoneProtocol() throws Exception {
        MockMvc mvc = legacy(new AgentExecutionProperties());
        when(chat.createDashScopeApi()).thenReturn(DashScopeApi.builder().apiKey("offline-test-only").build());
        OverAllState result = mock(OverAllState.class);
        when(aiOps.executeAiOpsAnalysis(any(), any())).thenReturn(Optional.of(result));
        when(aiOps.extractFinalReport(result)).thenReturn(Optional.of("# offline report"));
        for (String route : List.of("/api/flight_guard", "/api/campaign_guard", "/api/ai_ops")) {
            MvcResult pending = mvc.perform(post(route).header("X-Session-Id", route))
                    .andExpect(request().asyncStarted()).andReturn();
            pending.getAsyncResult(3000);
            String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("# offline report"));
            assertEquals(1, occurrences(body, "\"type\":\"done\""));
        }
    }

    private ReactAgent prepareChatSession() {
        ChatSessionService.SessionSnapshot snapshot = new ChatSessionService.SessionSnapshot();
        snapshot.setSessionId("chat-1");
        snapshot.setHistory(List.of());
        when(sessions.getOrCreateSession(any())).thenReturn(snapshot);
        when(chat.buildSystemPrompt(anyList())).thenReturn("system");
        ReactAgent agent = mock(ReactAgent.class);
        when(chat.createReactAgent(any(), eq("system"))).thenReturn(agent);
        return agent;
    }

    private int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
