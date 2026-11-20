package com.chatapp.chat;

/**
 * Data sent to the client. Times are epoch milliseconds.
 * Messages are ordered by {@code seq}; {@code createdAt} is only for display.
 */
public final class ChatModels {
    private ChatModels() {}

    public record MessageView(
            long id,
            long conversationId,
            long seq,
            long senderId,
            String clientMsgId,
            String body,
            long createdAt) {}

    public record Peer(long id, String email, String displayName) {}

    public record ConversationView(
            long id,
            Peer peer,
            long lastSeq,
            MessageView lastMessage,
            long updatedAt) {}

    /** Result of sending a message. {@code duplicate} is true if it was a retry of a saved message. */
    public record SendResult(MessageView message, long userLow, long userHigh, boolean duplicate) {}
}
