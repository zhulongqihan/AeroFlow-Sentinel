package io.github.zhulongqihan.aeroflow.sentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhulongqihan.aeroflow.sentinel.config.ChatSessionProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class ChatSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionService.class);

    private final ChatSessionProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock storageLock = new ReentrantReadWriteLock();

    public ChatSessionService(ChatSessionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    public SessionSnapshot getOrCreateSession(String sessionId) {
        storageLock.writeLock().lock();
        try {
            String resolvedSessionId = resolveSessionId(sessionId);
            StoredSession storedSession = sessions.computeIfAbsent(resolvedSessionId, this::createEmptySession);
            return toSnapshot(storedSession);
        } finally {
            storageLock.writeLock().unlock();
        }
    }

    public SessionSnapshot getSession(String sessionId) {
        storageLock.readLock().lock();
        try {
            StoredSession storedSession = sessions.get(sessionId);
            return storedSession == null ? null : toSnapshot(storedSession);
        } finally {
            storageLock.readLock().unlock();
        }
    }

    public SessionSnapshot appendMessagePair(String sessionId, String userQuestion, String aiAnswer) {
        storageLock.writeLock().lock();
        try {
            String resolvedSessionId = resolveSessionId(sessionId);
            StoredSession storedSession = sessions.computeIfAbsent(resolvedSessionId, this::createEmptySession);

            storedSession.getMessageHistory().add(createMessage("user", userQuestion));
            storedSession.getMessageHistory().add(createMessage("assistant", aiAnswer));
            trimHistory(storedSession);
            storedSession.setUpdateTime(System.currentTimeMillis());

            persistIfEnabled();
            return toSnapshot(storedSession);
        } finally {
            storageLock.writeLock().unlock();
        }
    }

    public boolean clearSession(String sessionId) {
        storageLock.writeLock().lock();
        try {
            StoredSession storedSession = sessions.get(sessionId);
            if (storedSession == null) {
                return false;
            }

            storedSession.getMessageHistory().clear();
            storedSession.setUpdateTime(System.currentTimeMillis());
            persistIfEnabled();
            return true;
        } finally {
            storageLock.writeLock().unlock();
        }
    }

    private StoredSession createEmptySession(String sessionId) {
        long now = System.currentTimeMillis();
        StoredSession storedSession = new StoredSession();
        storedSession.setSessionId(sessionId);
        storedSession.setCreateTime(now);
        storedSession.setUpdateTime(now);
        storedSession.setMessageHistory(new ArrayList<>());
        return storedSession;
    }

    private StoredMessage createMessage(String role, String content) {
        StoredMessage storedMessage = new StoredMessage();
        storedMessage.setRole(role);
        storedMessage.setContent(content);
        return storedMessage;
    }

    private void trimHistory(StoredSession storedSession) {
        int maxMessages = properties.getMaxWindowSize() * 2;
        while (storedSession.getMessageHistory().size() > maxMessages) {
            storedSession.getMessageHistory().remove(0);
        }
    }

    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId;
    }

    private SessionSnapshot toSnapshot(StoredSession storedSession) {
        List<Map<String, String>> history = new ArrayList<>();
        for (StoredMessage storedMessage : storedSession.getMessageHistory()) {
            Map<String, String> item = new HashMap<>();
            item.put("role", storedMessage.getRole());
            item.put("content", storedMessage.getContent());
            history.add(item);
        }

        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setSessionId(storedSession.getSessionId());
        snapshot.setHistory(history);
        snapshot.setCreateTime(storedSession.getCreateTime());
        snapshot.setUpdateTime(storedSession.getUpdateTime());
        snapshot.setMessagePairCount(history.size() / 2);
        return snapshot;
    }

    private void loadFromDisk() {
        if (!properties.isPersistenceEnabled()) {
            logger.info("会话持久化已关闭，使用纯内存模式");
            return;
        }

        Path persistenceFile = Paths.get(properties.getPersistencePath()).normalize();
        if (!Files.exists(persistenceFile)) {
            logger.info("未发现历史会话持久化文件: {}", persistenceFile);
            return;
        }

        storageLock.writeLock().lock();
        try {
            SessionsFile sessionsFile = objectMapper.readValue(persistenceFile.toFile(), SessionsFile.class);
            sessions.clear();
            if (sessionsFile.getSessions() != null) {
                for (StoredSession session : sessionsFile.getSessions()) {
                    if (session.getMessageHistory() == null) {
                        session.setMessageHistory(new ArrayList<>());
                    }
                    sessions.put(session.getSessionId(), session);
                }
            }
            logger.info("已加载 {} 个持久化会话", sessions.size());
        } catch (IOException e) {
            logger.error("读取持久化会话失败，继续使用空会话状态", e);
        } finally {
            storageLock.writeLock().unlock();
        }
    }

    private void persistIfEnabled() {
        if (!properties.isPersistenceEnabled()) {
            return;
        }

        Path persistenceFile = Paths.get(properties.getPersistencePath()).normalize();
        try {
            Path parent = persistenceFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            SessionsFile sessionsFile = new SessionsFile();
            sessionsFile.setSessions(new ArrayList<>(new LinkedHashMap<>(sessions).values()));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(persistenceFile.toFile(), sessionsFile);
        } catch (IOException e) {
            logger.error("持久化会话失败: {}", persistenceFile, e);
        }
    }

    public static class SessionSnapshot {
        private String sessionId;
        private List<Map<String, String>> history;
        private long createTime;
        private long updateTime;
        private int messagePairCount;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public List<Map<String, String>> getHistory() {
            return history;
        }

        public void setHistory(List<Map<String, String>> history) {
            this.history = history;
        }

        public long getCreateTime() {
            return createTime;
        }

        public void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        public long getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(long updateTime) {
            this.updateTime = updateTime;
        }

        public int getMessagePairCount() {
            return messagePairCount;
        }

        public void setMessagePairCount(int messagePairCount) {
            this.messagePairCount = messagePairCount;
        }
    }

    public static class SessionsFile {
        private List<StoredSession> sessions;

        public List<StoredSession> getSessions() {
            return sessions;
        }

        public void setSessions(List<StoredSession> sessions) {
            this.sessions = sessions;
        }
    }

    public static class StoredSession {
        private String sessionId;
        private List<StoredMessage> messageHistory;
        private long createTime;
        private long updateTime;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public List<StoredMessage> getMessageHistory() {
            return messageHistory;
        }

        public void setMessageHistory(List<StoredMessage> messageHistory) {
            this.messageHistory = messageHistory;
        }

        public long getCreateTime() {
            return createTime;
        }

        public void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        public long getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(long updateTime) {
            this.updateTime = updateTime;
        }
    }

    public static class StoredMessage {
        private String role;
        private String content;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}