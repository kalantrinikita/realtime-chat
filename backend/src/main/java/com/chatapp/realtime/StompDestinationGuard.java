package com.chatapp.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Locks down which STOMP destinations a browser may use. Without this, two holes exist by default:
 *
 * <ol>
 *   <li><b>Eavesdropping.</b> A subscription to {@code /user/queue/messages} is rewritten by Spring
 *       to a per-session queue such as {@code /queue/messages-user3}. The suffix is the WebSocket
 *       session id, which on Tomcat is a small incrementing number. Any client could SUBSCRIBE
 *       to {@code /queue/messages-user2} directly and read someone else's messages.</li>
 *   <li><b>Spoofing.</b> The simple broker accepts SEND to any broker destination, so a client
 *       could SEND straight to {@code /queue/messages-user2} and inject a fake message that never
 *       went through validation, the database or the sender check.</li>
 * </ol>
 *
 * Only the four personal queues may be subscribed to, and SEND is only allowed to /app/*
 * (our controller). Offending frames are dropped (returning null) rather than answered with an
 * exception: with ordered receiving enabled, Spring doesn't turn interceptor exceptions into
 * STOMP ERROR frames anyway, and silence gives a prober nothing to work with.
 */
@Component
public class StompDestinationGuard implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(StompDestinationGuard.class);

    static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of(
            "/user/queue/messages", "/user/queue/acks", "/user/queue/errors", "/user/queue/ready");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == null) return message; // heartbeats and internal messages
        String destination = accessor.getDestination();
        switch (command) {
            case SUBSCRIBE -> {
                if (destination == null || !ALLOWED_SUBSCRIPTIONS.contains(destination)) {
                    log.warn("Dropped SUBSCRIBE to {} from session {}", destination, accessor.getSessionId());
                    return null;
                }
            }
            case SEND -> {
                if (destination == null || !destination.startsWith("/app/")) {
                    log.warn("Dropped SEND to {} from session {}", destination, accessor.getSessionId());
                    return null;
                }
            }
            default -> {
                // CONNECT, DISCONNECT, UNSUBSCRIBE, ACK... are fine
            }
        }
        return message;
    }
}
