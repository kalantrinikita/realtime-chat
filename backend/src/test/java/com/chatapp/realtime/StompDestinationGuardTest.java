package com.chatapp.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StompDestinationGuardTest {
    private final StompDestinationGuard guard = new StompDestinationGuard();
    private final MessageChannel channel = (message, timeout) -> true;

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("3");
        if (destination != null) accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void allowsSubscribingToYourOwnQueues() {
        for (String d : StompDestinationGuard.ALLOWED_SUBSCRIPTIONS) {
            assertNotNull(guard.preSend(frame(StompCommand.SUBSCRIBE, d), channel), d);
        }
    }

    @Test
    void blocksSubscribingToAnotherSessionsResolvedQueue() {
        // The internal queue name for session "2" must be refused.
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, "/queue/messages-user2"), channel));
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, "/queue/messages"), channel));
        assertNull(guard.preSend(frame(StompCommand.SUBSCRIBE, null), channel));
    }

    @Test
    void sendOnlyReachesTheApplicationNeverTheBrokerDirectly() {
        assertNotNull(guard.preSend(frame(StompCommand.SEND, "/app/send"), channel));
        assertNull(guard.preSend(frame(StompCommand.SEND, "/queue/messages-user2"), channel));
        assertNull(guard.preSend(frame(StompCommand.SEND, "/user/2/queue/messages"), channel));
    }

    @Test
    void otherFramesPassThrough() {
        assertNotNull(guard.preSend(frame(StompCommand.CONNECT, null), channel));
        assertNotNull(guard.preSend(frame(StompCommand.DISCONNECT, null), channel));
    }
}
