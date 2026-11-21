package com.chatapp.realtime;

import com.chatapp.auth.User;
import com.chatapp.chat.ChatModels.MessageView;

/** Messages sent over the WebSocket. Each one has a "type" field so the browser knows what it is. */
public final class RealtimeEvents {
    public static final int PROTOCOL_VERSION = 1;

    private RealtimeEvents() {}

    /** Sent by the browser to /app/send. */
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
