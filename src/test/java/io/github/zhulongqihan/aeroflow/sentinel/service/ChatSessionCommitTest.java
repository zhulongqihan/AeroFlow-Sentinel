package io.github.zhulongqihan.aeroflow.sentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhulongqihan.aeroflow.sentinel.config.ChatSessionProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatSessionCommitTest {
    @Test
    void rejectedCommitGuardDoesNotCreateOrAppendHistory() {
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setPersistenceEnabled(false);
        ChatSessionService sessions = new ChatSessionService(properties, new ObjectMapper());
        assertThrows(AgentExecutionService.TaskCancelledException.class,
                () -> sessions.appendMessagePair("cancelled", "question", "late", () -> {
                    throw new AgentExecutionService.TaskCancelledException();
                }));
        assertNull(sessions.getSession("cancelled"));
    }

    @Test
    void originalThreeArgumentCallKeepsItsHistoryWindowBehavior() {
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setPersistenceEnabled(false);
        properties.setMaxWindowSize(1);
        ChatSessionService sessions = new ChatSessionService(properties, new ObjectMapper());
        sessions.appendMessagePair("legacy", "first", "first answer");
        ChatSessionService.SessionSnapshot latest = sessions.appendMessagePair("legacy", "second", "second answer");
        assertEquals(1, latest.getMessagePairCount());
        assertEquals("second", latest.getHistory().get(0).get("content"));
        assertEquals("second answer", latest.getHistory().get(1).get("content"));
    }
}
