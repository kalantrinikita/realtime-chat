package com.chatapp.realtime;

import com.chatapp.auth.User;
import com.chatapp.chat.ChatModels.SendResult;
import com.chatapp.chat.ChatService;
import com.chatapp.common.AppException;
import com.chatapp.realtime.RealtimeEvents.AckEvent;
import com.chatapp.realtime.RealtimeEvents.ErrorEvent;
import com.chatapp.realtime.RealtimeEvents.MessageEvent;
import com.chatapp.realtime.RealtimeEvents.ReadyEvent;
import com.chatapp.realtime.RealtimeEvents.SendCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/** Handles messages sent over the WebSocket: hello and send. */
@Controller
public class ChatMessagingController {
    private static final Logger log = LoggerFactory.getLogger(ChatMessagingController.class);

    private final ChatService chat;
    private final SimpMessagingTemplate messaging;

    public ChatMessagingController(ChatService chat, SimpMessagingTemplate messaging) {
        this.chat = chat;
        this.messaging = messaging;
    }

    /**
     * The browser sends this after subscribing. The "ready" reply tells it the subscriptions are
     * active, so it can now load any messages it missed.
     */
    @MessageMapping("/hello")
    @SendToUser(destinations = "/queue/ready", broadcast = false)
    public ReadyEvent hello(SimpMessageHeaderAccessor headers) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        User user = attributes == null ? null : (User) attributes.get(SessionHandshakeInterceptor.USER);
        if (user == null) throw new IllegalStateException("STOMP session without an authenticated user");
        return new ReadyEvent(user);
    }

    /**
     * Saves the message, acks it to the sending tab, then pushes it to every open tab of both users.
     */
    @MessageMapping("/send")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public AckEvent send(@Payload SendCommand cmd, Principal principal) {
        String clientMsgId = cmd.clientMsgId();
        try {
            UUID id = parseClientMsgId(clientMsgId);
            if (cmd.conversationId() == null) throw AppException.badRequest("conversationId is required");
            long senderId = Long.parseLong(principal.getName());

            SendResult result = chat.sendMessage(senderId, cmd.conversationId(), id, cmd.body());

            // Saved by now. A retry is acked again but not pushed again.
            if (!result.duplicate()) {
                MessageEvent event = new MessageEvent(result.message());
                messaging.convertAndSendToUser(String.valueOf(result.userLow()), "/queue/messages", event);
                messaging.convertAndSendToUser(String.valueOf(result.userHigh()), "/queue/messages", event);
            }
            return new AckEvent(clientMsgId, result.message());
        } catch (AppException e) {
            throw new SendRejectedException(clientMsgId, e.code(), e.getMessage(), e.retryable());
        } catch (RuntimeException e) {
            log.error("send failed", e);
            throw new SendRejectedException(clientMsgId, "internal", "Could not send message", true);
        }
    }

    /** Errors go back only to the tab that sent the message, tagged with its clientMsgId. */
    @MessageExceptionHandler(SendRejectedException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorEvent rejected(SendRejectedException e) {
        return new ErrorEvent(e.clientMsgId, e.code, e.getMessage(), e.retryable);
    }

    private static UUID parseClientMsgId(String value) {
        if (value == null) throw AppException.badRequest("clientMsgId is required");
        try {
            UUID uuid = UUID.fromString(value);
            // Only accept the standard 36-character format.
            if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return uuid;
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("clientMsgId must be a UUID");
        }
    }

    static final class SendRejectedException extends RuntimeException {
        final String clientMsgId;
        final String code;
        final boolean retryable;

        SendRejectedException(String clientMsgId, String code, String message, boolean retryable) {
            super(message);
            this.clientMsgId = clientMsgId;
            this.code = code;
            this.retryable = retryable;
        }
    }
}
