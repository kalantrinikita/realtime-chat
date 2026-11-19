package com.chatapp.chat;

/**
 * Wire-facing views. Timestamps are epoch milliseconds: unambiguous across time zones, trivially
 * parsed by JavaScript, and they keep Jackson free of java.time modules.
 *
 * <p>Ordering never uses {@code createdAt}: device clocks drift, and two messages can share a
 * millisecond. {@code seq} is the only ordering key; {@code createdAt} is for display.
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

    /**
     * Result of a send. {@code duplicate} means the client retried a message we had already stored;
     * the caller acks it again but must not fan it out a second time.
     */
    public record SendResult(MessageView message, long userLow, long userHigh, boolean duplicate) {}
}
