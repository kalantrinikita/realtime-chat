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

/**
 * The live side of chat. Spring routes STOMP frames here; everything about what a "message" means
 * (who may send where, its number, de-duplication, who receives it) is decided in this class and
 * in ChatService.
 */
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
     * STOMP SUBSCRIBE has no acknowledgement, so a client can't tell when its subscriptions are
     * live. If it fetched missed messages right after subscribing, a message arriving in between
     * could be in neither the fetch nor the push and be silently lost.
     *
     * The client therefore subscribes first, then sends /app/hello. Because receive order is
     * preserved per session (WebSocketConfig), the SUBSCRIBE frames have been fully processed by the
     * time this runs, so "ready" proves the subscriptions are live. Only then does the client fetch
     * what it missed.
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
     * Store, then acknowledge to the sending tab, then push to every open tab of both people.
     * The ack goes only to the session that sent it (broadcast = false); the push goes to all of
     * the sender's sessions too, so their other tabs and devices stay in sync. The sending tab
     * receives both and de-duplicates by seq.
     *
     * Runs on the inbound channel's thread for this session, one frame at a time, so two
     * messages typed quickly in one tab are stored in the order they were sent.
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

            // Committed by now. A retry of an already-stored message is acked again but not
            // pushed again; anyone who missed the first push catches up by seq.
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
            // UUID.fromString also accepts non-canonical forms like "1-1-1-1-1". Requiring the
            // canonical 36-character form means one id can't be written two ways to dodge de-duplication.
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
