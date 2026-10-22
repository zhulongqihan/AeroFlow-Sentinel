package io.github.zhulongqihan.aeroflow.sentinel.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.github.zhulongqihan.aeroflow.sentinel.service.AgentExecutionService;
import io.github.zhulongqihan.aeroflow.sentinel.service.AiOpsService;
import io.github.zhulongqihan.aeroflow.sentinel.service.ChatService;
import io.github.zhulongqihan.aeroflow.sentinel.service.ChatSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** v1 endpoints retain their payloads while sharing bounded admission and deadlines with v2. */
@RestController
@RequestMapping("/api")
public class ChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private final AiOpsService aiOpsService;
    private final ChatService chatService;
    private final ChatSessionService chatSessionService;
    private final AgentExecutionService executionService;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    public ChatController(AiOpsService aiOpsService, ChatService chatService,
                          ChatSessionService chatSessionService, AgentExecutionService executionService) {
        this.aiOpsService = aiOpsService;
        this.chatService = chatService;
        this.chatSessionService = chatSessionService;
        this.executionService = executionService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request,
                                                         HttpServletRequest servletRequest) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
        }
        AgentExecutionService.Task<ChatResponse> task = executionService.submitLegacy(
                sessionKey(request.getId(), servletRequest), () -> {
                    ChatSessionService.SessionSnapshot session = chatSessionService.getOrCreateSession(request.getId());
                    executionService.ensureActive();
                    ReactAgent agent = createChatAgent(session);
                    String answer = chatService.executeChat(agent, request.getQuestion());
                    executionService.ensureActive();
                    chatSessionService.appendMessagePair(session.getSessionId(), request.getQuestion(), answer,
                            executionService::ensureActive);
                    return ChatResponse.success(answer);
                });
        try {
            return ResponseEntity.ok(ApiResponse.success(task.completion().get()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel();
            return ResponseEntity.status(503).body(ApiResponse.success(ChatResponse.error("请求已取消，请稍后重试")));
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            int status = cause instanceof AgentExecutionService.DeadlineExceededException ? 504 : 503;
            return ResponseEntity.status(status).body(ApiResponse.success(ChatResponse.error(failureMessage(cause))));
        }
    }

    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        if (request.getId() == null || request.getId().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
        }
        return ResponseEntity.ok(chatSessionService.clearSession(request.getId())
                ? ApiResponse.success("会话历史已清空") : ApiResponse.error("会话不存在"));
    }

    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request, HttpServletRequest servletRequest) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            SseEmitter emitter = new SseEmitter(300_000L);
            sendTerminal(emitter, SseMessage.error("问题内容不能为空"));
            return emitter;
        }
        return startStream(sessionKey(request.getId(), servletRequest), (emitter, closed) -> {
            ChatSessionService.SessionSnapshot session = chatSessionService.getOrCreateSession(request.getId());
            executionService.ensureActive();
            ReactAgent agent = createChatAgent(session);
            StringBuilder answer = new StringBuilder();
            // blockLast owns and disposes its subscription when the bounded worker is interrupted.
            agent.stream(request.getQuestion()).doOnNext(output -> {
                if (closed.get()) throw new AgentExecutionService.TaskCancelledException();
                if (output instanceof StreamingOutput streaming
                        && streaming.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
                    String chunk = streaming.message().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        answer.append(chunk);
                        send(emitter, closed, SseMessage.content(chunk));
                    }
                }
            }).blockLast();
            executionService.ensureActive();
            if (closed.get()) throw new AgentExecutionService.TaskCancelledException();
            chatSessionService.appendMessagePair(session.getSessionId(), request.getQuestion(), answer.toString(), () -> {
                executionService.ensureActive();
                if (closed.get()) throw new AgentExecutionService.TaskCancelledException();
            });
        });
    }

    @PostMapping(value = {"/flight_guard", "/campaign_guard", "/ai_ops"}, produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                           HttpServletRequest servletRequest) {
        return startStream(sessionKey(sessionId, servletRequest), (emitter, closed) -> {
            DashScopeApi api = chatService.createDashScopeApi();
            DashScopeChatModel model = DashScopeChatModel.builder().dashScopeApi(api)
                    .defaultOptions(DashScopeChatOptions.builder()
                            .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                            .withTemperature(0.3).withMaxToken(8000).withTopP(0.9).build()).build();
            ToolCallback[] callbacks = tools == null ? new ToolCallback[0] : tools.getToolCallbacks();
            send(emitter, closed, SseMessage.content("正在读取航旅预订链路风险事件并拆解任务...\n"));
            var state = aiOpsService.executeAiOpsAnalysis(model, callbacks);
            if (state.isEmpty()) throw new IllegalStateException("巡检未获取到有效结果");
            Optional<String> report = aiOpsService.extractFinalReport(state.get());
            if (report.isEmpty()) throw new IllegalStateException("巡检未生成有效报告");
            send(emitter, closed, SseMessage.content("\n\n" + "=".repeat(60) + "\n"));
            send(emitter, closed, SseMessage.content("📋 **航旅预订链路稳定性分析报告**\n\n"));
            for (int offset = 0; offset < report.get().length(); offset += 50) {
                send(emitter, closed, SseMessage.content(report.get().substring(offset,
                        Math.min(offset + 50, report.get().length()))));
            }
            send(emitter, closed, SseMessage.content("\n" + "=".repeat(60) + "\n\n"));
        });
    }

    private ReactAgent createChatAgent(ChatSessionService.SessionSnapshot session) {
        return chatService.createReactAgent(chatService.createStandardChatModel(chatService.createDashScopeApi()),
                chatService.buildSystemPrompt(session.getHistory()));
    }

    private String sessionKey(String id, HttpServletRequest request) {
        return id == null || id.isBlank() ? "source:" + request.getRemoteAddr() : "session:" + id.trim();
    }

    private SseEmitter startStream(String key, StreamWork work) {
        AgentExecutionService.CompletionDelivery delivery = executionService.reserveCompletion();
        try {
            SseEmitter emitter = new SseEmitter(600_000L);
            AtomicBoolean closed = new AtomicBoolean();
            AtomicReference<AgentExecutionService.Task<Void>> active = new AtomicReference<>();
            Runnable cancel = () -> {
                AgentExecutionService.Task<Void> task = active.get();
                if (task != null && !closed.get()) task.cancel();
            };
            emitter.onTimeout(cancel);
            emitter.onError(error -> cancel.run());
            emitter.onCompletion(cancel);
            AgentExecutionService.Task<Void> task = executionService.submitLegacy(key, () -> {
                work.run(emitter, closed);
                return null;
            });
            active.set(task);
            task.completion().whenComplete((ignored, failure) -> {
                if (closed.compareAndSet(false, true)) {
                    delivery.dispatch(() -> sendTerminal(emitter,
                            failure == null ? SseMessage.done() : SseMessage.error(failureMessage(failure))));
                }
            });
            return emitter;
        } catch (RuntimeException failure) {
            delivery.close();
            throw failure;
        }
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, SseMessage message) {
        if (closed.get()) throw new AgentExecutionService.TaskCancelledException();
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("message").data(message, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException disconnected) {
            throw new AgentExecutionService.TaskCancelledException();
        }
    }

    private void sendTerminal(SseEmitter emitter, SseMessage message) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("message").data(message, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException disconnected) {
            logger.debug("SSE connection completed before its terminal message");
        } finally {
            emitter.complete();
        }
    }

    private String failureMessage(Throwable failure) {
        while (failure.getCause() != null && !(failure instanceof AgentExecutionService.DeadlineExceededException)
                && !(failure instanceof AgentExecutionService.TaskCancelledException)) failure = failure.getCause();
        if (failure instanceof AgentExecutionService.DeadlineExceededException) return "任务超过运行时间预算，请稍后重试";
        if (failure instanceof AgentExecutionService.TaskCancelledException) return "任务已取消";
        return "服务暂时不可用，请稍后重试";
    }

    @FunctionalInterface
    private interface StreamWork {
        void run(SseEmitter emitter, AtomicBoolean closed) throws Exception;
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            ChatSessionService.SessionSnapshot session = chatSessionService.getSession(sessionId);
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(session.getSessionId());
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.getCreateTime());
                response.setUpdateTime(session.getUpdateTime());
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
        private long updateTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
