package io.github.zhulongqihan.aeroflow.sentinel.service;

import io.github.zhulongqihan.aeroflow.sentinel.config.AgentExecutionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentExecutionServiceTest {
    private AgentExecutionService execution;

    @AfterEach void close() { if (execution != null) execution.shutdown(); }

    @Test
    void sessionAdmissionIsSharedAcrossV1V2AndReleasedOnCancellation() throws Exception {
        execution = new AgentExecutionService(new AgentExecutionProperties());
        CountDownLatch entered = new CountDownLatch(1);
        AgentExecutionService.Task<String> task = execution.submitLegacy("session-one", () -> {
            entered.countDown();
            new CountDownLatch(1).await();
            return "unreachable";
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertThrows(AgentExecutionService.OverloadedException.class,
                () -> execution.submitRun("session-one", () -> "should not start"));
        task.cancel();
        assertThrows(ExecutionException.class, () -> task.completion().get(1, TimeUnit.SECONDS));
        assertEquals("next", execution.submitRun("session-one", () -> "next").completion().get(1, TimeUnit.SECONDS));
        assertEquals(0, execution.sessionCount());
    }

    @Test
    void runQueueRejectsExcessAndRemovingCancelledQueueEntryAllowsRecovery() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setRunThreads(1);
        properties.setRunQueueCapacity(1);
        execution = new AgentExecutionService(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var first = execution.submitRun("one", () -> { entered.countDown(); release.await(); return 1; });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        var queued = execution.submitRun("two", () -> 2);
        assertThrows(AgentExecutionService.OverloadedException.class, () -> execution.submitRun("three", () -> 3));
        queued.cancel();
        var replacement = execution.submitRun("three", () -> 3);
        release.countDown();
        assertEquals(1, first.completion().get(1, TimeUnit.SECONDS));
        assertEquals(3, replacement.completion().get(1, TimeUnit.SECONDS));
    }

    @Test
    void deadlineCompletesOnceInterruptsWorkerAndClearsTimerAndAdmission() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setRunTimeoutMs(80);
        execution = new AgentExecutionService(properties);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        var task = execution.submitRun("one", () -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
            return "late";
        });
        task.completion().whenComplete((value, failure) -> completions.incrementAndGet());
        ExecutionException failure = assertThrows(ExecutionException.class, () -> task.completion().get(2, TimeUnit.SECONDS));
        assertInstanceOf(AgentExecutionService.DeadlineExceededException.class, failure.getCause());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        task.cancel();
        assertEquals(1, completions.get());
        assertEquals(0, execution.sessionCount());
        assertEquals(0, execution.scheduledCount());
    }

    @Test
    void interruptIgnoringToolCannotCauseNewWorkerCreationOrAnUnboundedQueue() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setToolThreads(1);
        properties.setToolQueueCapacity(1);
        properties.setToolTimeoutMs(120);
        execution = new AgentExecutionService(properties);
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        try {
            var stuck = execution.submitTool(1000, () -> {
                entered.countDown();
                while (!release.get()) {
                    try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                }
                return "late";
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> stuck.completion().get(1, TimeUnit.SECONDS));
            var queued = execution.submitTool(1000, () -> "queued");
            assertThrows(AgentExecutionService.OverloadedException.class, () -> execution.submitTool(1000, () -> "excess"));
            assertEquals(1, execution.toolWorkerCount());
            assertEquals(1, execution.queuedTools());
            assertThrows(ExecutionException.class, () -> queued.completion().get(1, TimeUnit.SECONDS));
            assertEquals(0, execution.queuedTools());
            assertEquals(1, execution.toolWorkerCount());
        } finally { release.set(true); }
    }

    @Test
    void completionRejectionCannotBlockAnUnrelatedDeadline() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setRunTimeoutMs(100);
        execution = new AgentExecutionService(properties);
        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch releaseSend = new CountDownLatch(1);
        ThreadPoolExecutor completions = (ThreadPoolExecutor) ReflectionTestUtils.getField(execution, "completions");
        assertNotNull(completions);
        Runnable slowSend = () -> {
            occupied.countDown();
            awaitIgnoringInterruption(releaseSend);
        };
        completions.execute(slowSend);
        completions.execute(slowSend);
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        int capacity = properties.getRunThreads() + properties.getRunQueueCapacity()
                + properties.getLegacyThreads() + properties.getLegacyQueueCapacity();
        for (int i = 0; i < capacity; i++) completions.execute(() -> { });
        AgentExecutionService.CompletionDelivery delivery = execution.reserveCompletion();
        CountDownLatch deliveryRejected = new CountDownLatch(1);
        try {
            var first = execution.submitRun("first", () -> { new CountDownLatch(1).await(); return 1; });
            first.completion().whenComplete((value, failure) -> {
                assertFalse(delivery.dispatch(() -> fail("rejected terminal must not execute inline")));
                deliveryRejected.countDown();
            });
            assertTrue(deliveryRejected.await(1, TimeUnit.SECONDS));
            var second = execution.submitRun("second", () -> { new CountDownLatch(1).await(); return 2; });
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> second.completion().get(500, TimeUnit.MILLISECONDS));
            assertInstanceOf(AgentExecutionService.DeadlineExceededException.class, failure.getCause());
        } finally {
            releaseSend.countDown();
        }
    }

    @Test
    void slowTerminalClientsBackPressureNewStreamsAndReservationsRecover() throws Exception {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setRunThreads(1);
        properties.setRunQueueCapacity(1);
        properties.setLegacyThreads(1);
        properties.setLegacyQueueCapacity(1);
        execution = new AgentExecutionService(properties);
        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch releaseSend = new CountDownLatch(1);
        java.util.List<AgentExecutionService.CompletionDelivery> deliveries = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) deliveries.add(execution.reserveCompletion());
        try {
            assertTrue(deliveries.get(0).dispatch(() -> { occupied.countDown(); awaitIgnoringInterruption(releaseSend); }));
            assertTrue(deliveries.get(1).dispatch(() -> { occupied.countDown(); awaitIgnoringInterruption(releaseSend); }));
            assertTrue(occupied.await(1, TimeUnit.SECONDS));
            assertTrue(deliveries.get(2).dispatch(() -> { }));
            assertThrows(AgentExecutionService.OverloadedException.class, execution::reserveCompletion);
            // Abandoning a request before submission returns its one reservation exactly once.
            deliveries.get(3).close();
            deliveries.get(3).close();
            AgentExecutionService.CompletionDelivery recovered = execution.reserveCompletion();
            assertThrows(AgentExecutionService.OverloadedException.class, execution::reserveCompletion);
            recovered.close();
            // A caller cannot free a reservation while its slow write is still in progress.
            deliveries.get(0).close();
            try (var onlyFreeSlot = execution.reserveCompletion()) {
                assertThrows(AgentExecutionService.OverloadedException.class, execution::reserveCompletion);
            }
        } finally {
            releaseSend.countDown();
            deliveries.forEach(AgentExecutionService.CompletionDelivery::close);
        }
    }

    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() > 0) {
            try { latch.await(); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
