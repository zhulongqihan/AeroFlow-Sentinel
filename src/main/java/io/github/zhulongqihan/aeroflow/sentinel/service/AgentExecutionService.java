package io.github.zhulongqihan.aeroflow.sentinel.service;

import io.github.zhulongqihan.aeroflow.sentinel.config.AgentExecutionProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded workers plus a separate watchdog; cancellation never replaces a stuck worker. */
@Service
public class AgentExecutionService {
    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionService.class);
    private final AgentExecutionProperties properties;
    private final ThreadPoolExecutor runs;
    private final ThreadPoolExecutor tools;
    private final ThreadPoolExecutor legacy;
    private final ThreadPoolExecutor completions;
    private final Semaphore completionSlots;
    private final ScheduledThreadPoolExecutor watchdog;
    private final Set<Task<?>> tasks = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> sessions = new HashMap<>();
    private final ThreadLocal<Task<?>> currentTask = new ThreadLocal<>();

    public AgentExecutionService(AgentExecutionProperties properties) {
        this.properties = properties;
        requirePositive(properties.getPerSessionConcurrency(), "per-session-concurrency");
        requirePositive(properties.getToolTimeoutMs(), "tool-timeout-ms");
        requirePositive(properties.getRunTimeoutMs(), "run-timeout-ms");
        requirePositive(properties.getLegacyTimeoutMs(), "legacy-timeout-ms");
        runs = pool("agent-run", properties.getRunThreads(), properties.getRunQueueCapacity());
        tools = pool("agent-tool", properties.getToolThreads(), properties.getToolQueueCapacity());
        legacy = pool("agent-legacy", properties.getLegacyThreads(), properties.getLegacyQueueCapacity());
        int deliveryCapacity = properties.getRunThreads() + properties.getRunQueueCapacity()
                + properties.getLegacyThreads() + properties.getLegacyQueueCapacity();
        // Reserve before accepting a stream; slow terminal writes then back-pressure new streams.
        // Two extra worker positions provide headroom while a delivery releases its reservation.
        completions = pool("agent-completion", 2, deliveryCapacity);
        completionSlots = new Semaphore(deliveryCapacity);
        watchdog = new ScheduledThreadPoolExecutor(1, threads("agent-watchdog"));
        watchdog.setRemoveOnCancelPolicy(true);
        watchdog.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public <T> Task<T> submitRun(String session, Callable<T> action) {
        return submit(runs, session, properties.getRunTimeoutMs(), action);
    }

    public <T> Task<T> submitLegacy(String session, Callable<T> action) {
        return submit(legacy, session, properties.getLegacyTimeoutMs(), action);
    }

    public <T> Task<T> submitTool(long remainingMs, Callable<T> action) {
        return submit(tools, null, Math.max(1, Math.min(remainingMs, properties.getToolTimeoutMs())), action);
    }

    private synchronized <T> Task<T> submit(ThreadPoolExecutor executor, String session, long timeoutMs, Callable<T> action) {
        if (watchdog.isShutdown()) throw new OverloadedException();
        Runnable release = acquire(session);
        Task<T> task = new Task<>(executor, release);
        FutureTask<Void> worker = new FutureTask<>(() -> {
            if (!task.completion.isDone()) {
                currentTask.set(task);
                try {
                    T value = action.call();
                    task.succeed(value);
                } catch (Throwable failure) {
                    task.fail(failure);
                } finally {
                    currentTask.remove();
                }
            }
            return null;
        });
        task.worker.set(worker);
        task.cleanup = () -> {
            task.release.run();
            ScheduledFuture<?> timer = task.timer.get();
            if (timer != null) timer.cancel(false);
            tasks.remove(task);
        };
        tasks.add(task);
        try {
            executor.execute(worker);
            ScheduledFuture<?> timer = watchdog.schedule(
                    () -> task.stop(new DeadlineExceededException()), timeoutMs, TimeUnit.MILLISECONDS);
            task.timer.set(timer);
            if (task.completion.isDone()) timer.cancel(false);
            return task;
        } catch (RejectedExecutionException rejected) {
            task.stop(new OverloadedException());
            throw new OverloadedException();
        }
    }

    private Runnable acquire(String session) {
        if (session == null) return () -> { };
        String key = session.length() > 256 ? session.substring(0, 256) : session;
        synchronized (sessions) {
            int active = sessions.getOrDefault(key, 0);
            if (active >= properties.getPerSessionConcurrency()) throw new OverloadedException();
            sessions.put(key, active + 1);
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                synchronized (sessions) {
                    int remaining = sessions.getOrDefault(key, 1) - 1;
                    if (remaining <= 0) sessions.remove(key);
                    else sessions.put(key, remaining);
                }
            }
        };
    }

    private ThreadPoolExecutor pool(String name, int workers, int capacity) {
        requirePositive(workers, name + "-threads");
        requirePositive(capacity, name + "-queue-capacity");
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(capacity), threads(name), new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return action -> {
            Thread thread = new Thread(action, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException("agent.execution." + name + " must be positive");
    }

    public long runTimeoutMs() { return properties.getRunTimeoutMs(); }

    public void ensureActive() {
        Task<?> task = currentTask.get();
        if (Thread.currentThread().isInterrupted() || task != null && task.terminal.get()) throw new TaskCancelledException();
    }

    public synchronized CompletionDelivery reserveCompletion() {
        if (watchdog.isShutdown() || !completionSlots.tryAcquire()) throw new OverloadedException();
        return new CompletionDelivery();
    }

    /** No emitter method, including complete(), may run inline on the watchdog. */
    public final class CompletionDelivery implements AutoCloseable {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        public boolean dispatch(Runnable send) {
            if (!claimed.compareAndSet(false, true)) return false;
            try {
                completions.execute(() -> {
                    try { send.run(); }
                    finally { release(); }
                });
                return true;
            } catch (RejectedExecutionException stopped) {
                // Admission keeps the live queue below capacity. On shutdown, leave transport
                // cleanup to the servlet lifecycle rather than waiting on its output lock here.
                release();
                logger.debug("Terminal delivery executor is unavailable; servlet lifecycle will close the stream");
                return false;
            }
        }

        @Override
        public void close() {
            if (claimed.compareAndSet(false, true)) release();
        }

        private void release() {
            if (released.compareAndSet(false, true)) completionSlots.release();
        }
    }

    int sessionCount() {
        synchronized (sessions) { return sessions.size(); }
    }

    int toolWorkerCount() { return tools.getPoolSize(); }
    int queuedTools() { return tools.getQueue().size(); }
    int scheduledCount() { return watchdog.getQueue().size(); }

    @PreDestroy
    public synchronized void shutdown() {
        for (Task<?> task : tasks) task.cancel();
        runs.shutdownNow();
        tools.shutdownNow();
        legacy.shutdownNow();
        completions.shutdownNow();
        watchdog.shutdownNow();
    }

    public static final class Task<T> {
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private final AtomicReference<FutureTask<Void>> worker = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final ThreadPoolExecutor executor;
        private final Runnable release;
        private Runnable cleanup;

        private Task(ThreadPoolExecutor executor, Runnable release) {
            this.executor = executor;
            this.release = release;
        }

        public CompletableFuture<T> completion() { return completion; }

        public void cancel() { stop(new TaskCancelledException()); }

        private void succeed(T value) {
            if (terminal.compareAndSet(false, true)) {
                cleanup.run();
                completion.complete(value);
            }
        }

        private void fail(Throwable failure) {
            if (terminal.compareAndSet(false, true)) {
                cleanup.run();
                completion.completeExceptionally(failure);
            }
        }

        private void stop(RuntimeException reason) {
            if (terminal.compareAndSet(false, true)) {
                FutureTask<Void> current = worker.get();
                if (current != null) {
                    current.cancel(true);
                    executor.remove(current);
                }
                cleanup.run();
                completion.completeExceptionally(reason);
            }
        }
    }

    public static class OverloadedException extends RuntimeException {
        public OverloadedException() { super("当前任务较多，请稍后重试"); }
    }

    public static class DeadlineExceededException extends RuntimeException {
        public DeadlineExceededException() { super("任务超过运行时间预算，请稍后重试"); }
    }

    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException() { super("任务已取消"); }
    }
}
