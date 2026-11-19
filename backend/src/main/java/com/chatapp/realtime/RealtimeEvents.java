package com.chatapp.realtime;

import com.chatapp.auth.User;
import com.chatapp.chat.ChatModels.MessageView;

/**
 * JSON payloads sent over STOMP. Each carries a "type" so the browser can treat every queue with
 * one handler, and both sides ignore fields they don't know: a tab left open for days runs last
 * week's JavaScript, and a deploy must not break it.
 */
public final class RealtimeEvents {
    public static final int PROTOCOL_VERSION = 1;

    private RealtimeEvents() {}

    /** client -> /app/send. Boxed types so a missing field is a validation error, not a silent 0. */
    public record SendCommand(String clientMsgId, Long conversationId, String body) {}

    public record ReadyEvent(String type, int protocol, User user) {
        public ReadyEvent(User user) {
            this("ready", PROTOCOL_VERSION, user);
        }
    }

    public record AckEvent(String type, String clientMsgId, MessageView message) {
        public AckEvent(String clientMsgId, MessageView message) {
            this("ack", clientMsgId, message);
        }
    }

    public record MessageEvent(String type, MessageView message) {
        public MessageEvent(MessageView message) {
            this("message", message);
        }
    }

    public record ErrorEvent(String type, String clientMsgId, String code, String message, boolean retryable) {
        public ErrorEvent(String clientMsgId, String code, String message, boolean retryable) {
            this("error", clientMsgId, code, message, retryable);
        }
    }
}
