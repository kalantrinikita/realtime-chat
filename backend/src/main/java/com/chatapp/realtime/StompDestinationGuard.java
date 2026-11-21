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
 * Controls what a browser can do over STOMP: subscribe only to its own queues below,
 * and send only to /app/*. Anything else is dropped.
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
