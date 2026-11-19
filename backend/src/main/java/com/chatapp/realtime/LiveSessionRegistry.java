package com.chatapp.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a handle on every open WebSocket so we can close the ones belonging to a login session
 * when it logs out. Spring's broker tracks subscriptions, not "which sockets were opened with
 * which cookie", so this small registry fills that gap.
 */
@Component
public class LiveSessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(LiveSessionRegistry.class);
    /** Application close code (4000-4999 range): tells the browser "you were logged out, don't reconnect". */
    public static final CloseStatus LOGGED_OUT = new CloseStatus(4001, "Logged out");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    public void closeSession(byte[] tokenHash) {
        for (WebSocketSession s : sessions.values()) {
            Object hash = s.getAttributes().get(SessionHandshakeInterceptor.TOKEN_HASH);
            if (hash instanceof byte[] h && Arrays.equals(h, tokenHash)) {
                try {
                    s.close(LOGGED_OUT);
                } catch (IOException e) {
                    log.debug("Closing session {} failed: {}", s.getId(), e.getMessage());
                }
            }
        }
    }

    public int count() {
        return sessions.size();
    }
}
