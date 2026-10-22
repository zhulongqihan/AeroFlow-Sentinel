package io.github.zhulongqihan.aeroflow.sentinel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhulongqihan.aeroflow.sentinel.config.AgentExecutionProperties;
import io.github.zhulongqihan.aeroflow.sentinel.config.ChatSessionProperties;
import io.github.zhulongqihan.aeroflow.sentinel.service.AgentExecutionService;
import io.github.zhulongqihan.aeroflow.sentinel.service.AiOpsService;
import io.github.zhulongqihan.aeroflow.sentinel.service.ChatService;
import io.github.zhulongqihan.aeroflow.sentinel.service.ChatSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class ChatCancellationRegressionTest {
    @TempDir Path temporary;

    @Test
    void cancelledSessionLookupCannotStartTheModelAfterStorageUnlocks() throws Exception {
        ChatSessionProperties sessionProperties = new ChatSessionProperties();
        sessionProperties.setPersistenceEnabled(false);
        ChatSessionService sessions = new ChatSessionService(sessionProperties, new ObjectMapper());
        ReentrantReadWriteLock storageLock = (ReentrantReadWriteLock) ReflectionTestUtils.getField(sessions, "storageLock");
        assertNotNull(storageLock);
        ChatService chat = mock(ChatService.class);
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setLegacyTimeoutMs(300);
        AgentExecutionService execution = new AgentExecutionService(properties);
        var http = Executors.newSingleThreadExecutor();
        try {
            ChatController controller = new ChatController(mock(AiOpsService.class), chat, sessions, execution);
            ChatController.ChatRequest request = new ChatController.ChatRequest();
            request.setId("cancelled-lookup");
            request.setQuestion("question");
            storageLock.writeLock().lock();
            try {
                var response = http.submit(() -> controller.chat(request, new MockHttpServletRequest()));
                long queuedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (!storageLock.hasQueuedThreads() && System.nanoTime() < queuedDeadline) Thread.sleep(2);
                assertTrue(storageLock.hasQueuedThreads());
                assertEquals(504, response.get(2, TimeUnit.SECONDS).getStatusCode().value());
            } finally { storageLock.writeLock().unlock(); }
            ThreadPoolExecutor legacy = (ThreadPoolExecutor) ReflectionTestUtils.getField(execution, "legacy");
            assertNotNull(legacy);
            long idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (legacy.getActiveCount() > 0 && System.nanoTime() < idleDeadline) Thread.sleep(2);
            assertEquals(0, legacy.getActiveCount());
            verifyNoInteractions(chat);
        } finally {
            http.shutdownNow();
            execution.shutdown();
        }
    }

    @Test
    void deadlineWhileWaitingForStorageLockCannotCommitLateHistory() throws Exception {
        ChatSessionProperties sessionProperties = new ChatSessionProperties();
        Path historyFile = temporary.resolve("sessions.json");
        sessionProperties.setPersistencePath(historyFile.toString());
        ChatSessionService sessions = new ChatSessionService(sessionProperties, new ObjectMapper());
        sessions.getOrCreateSession("same-session");
        ChatService chat = mock(ChatService.class);
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch returnAnswer = new CountDownLatch(1);
        when(chat.executeChat(any(), anyString())).thenAnswer(call -> {
            modelEntered.countDown();
            assertTrue(returnAnswer.await(2, TimeUnit.SECONDS));
            return "late answer";
        });
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setLegacyTimeoutMs(500);
        AgentExecutionService execution = new AgentExecutionService(properties);
        var http = Executors.newSingleThreadExecutor();
        ReentrantReadWriteLock storageLock = (ReentrantReadWriteLock) ReflectionTestUtils.getField(sessions, "storageLock");
        assertNotNull(storageLock);
        try {
            ChatController controller = new ChatController(mock(AiOpsService.class), chat, sessions, execution);
            ChatController.ChatRequest request = new ChatController.ChatRequest();
            request.setId("same-session");
            request.setQuestion("question");
            var response = http.submit(() -> controller.chat(request, new MockHttpServletRequest()));
            assertTrue(modelEntered.await(1, TimeUnit.SECONDS));
            storageLock.writeLock().lock();
            try {
                returnAnswer.countDown();
                long queuedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (!storageLock.hasQueuedThreads() && System.nanoTime() < queuedDeadline) Thread.sleep(2);
                assertTrue(storageLock.hasQueuedThreads(), "worker must be waiting inside session storage");
                assertEquals(504, response.get(2, TimeUnit.SECONDS).getStatusCode().value());
            } finally { storageLock.writeLock().unlock(); }
            ThreadPoolExecutor legacy = (ThreadPoolExecutor) ReflectionTestUtils.getField(execution, "legacy");
            assertNotNull(legacy);
            long idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (legacy.getActiveCount() > 0 && System.nanoTime() < idleDeadline) Thread.sleep(2);
            assertEquals(0, legacy.getActiveCount());
            assertEquals(0, sessions.getSession("same-session").getMessagePairCount());
            assertFalse(Files.exists(historyFile), "cancelled waiter must not persist a late answer");
        } finally {
            returnAnswer.countDown();
            http.shutdownNow();
            execution.shutdown();
        }
    }
}
