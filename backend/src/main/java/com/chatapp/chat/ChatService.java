package com.chatapp.chat;

import com.chatapp.auth.User;
import com.chatapp.chat.ChatModels.ConversationView;
import com.chatapp.chat.ChatModels.MessageView;
import com.chatapp.chat.ChatModels.Peer;
import com.chatapp.chat.ChatModels.SendResult;
import com.chatapp.common.AppException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Conversations and messages. Used by both the REST and the WebSocket controllers. */
@Service
public class ChatService {
    /** Must match the CHECK constraint in V1__init.sql. */
    public static final int MAX_MESSAGE_CHARS = 4000;
    public static final int MAX_PAGE_SIZE = 100;

    private static final String MESSAGE_COLUMNS = "id, conversation_id, seq, sender_id, client_msg_id, body, created_at";
    private static final RowMapper<MessageView> MESSAGE = (rs, i) -> new MessageView(
            rs.getLong("id"), rs.getLong("conversation_id"), rs.getLong("seq"), rs.getLong("sender_id"),
            rs.getString("client_msg_id"), rs.getString("body"), rs.getTimestamp("created_at").getTime());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ChatService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Returns the conversation between two users, creating it if it doesn't exist yet. */
    public ConversationView startConversation(User me, User other) {
        if (me.id() == other.id()) throw AppException.badRequest("You can't start a conversation with yourself");
        long low = Math.min(me.id(), other.id());
        long high = Math.max(me.id(), other.id());
        jdbc.update("INSERT INTO conversations(user_low, user_high) VALUES (?, ?) ON CONFLICT (user_low, user_high) DO NOTHING", low, high);
        Long id = jdbc.queryForObject("SELECT id FROM conversations WHERE user_low = ? AND user_high = ?", Long.class, low, high);
        return listConversations(me.id()).stream()
                .filter(c -> c.id() == Objects.requireNonNull(id))
                .findFirst()
                .orElseThrow();
    }

    /** The user's conversations, most recent first, with the other person and the last message. */
    public List<ConversationView> listConversations(long me) {
        return jdbc.query("""
                SELECT c.id, c.last_seq, c.created_at, c.last_message_at,
                       u.id AS peer_id, u.email AS peer_email, u.display_name AS peer_name,
                       m.id AS m_id, m.seq AS m_seq, m.sender_id AS m_sender, m.client_msg_id AS m_cid,
                       m.body AS m_body, m.created_at AS m_created
                FROM conversations c
                JOIN users u ON u.id = CASE WHEN c.user_low = ? THEN c.user_high ELSE c.user_low END
                LEFT JOIN messages m ON m.conversation_id = c.id AND m.seq = c.last_seq
                WHERE c.user_low = ? OR c.user_high = ?
                ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC""",
                (rs, i) -> {
                    long convId = rs.getLong("id");
                    Peer peer = new Peer(rs.getLong("peer_id"), rs.getString("peer_email"), rs.getString("peer_name"));
                    MessageView last = rs.getObject("m_id") == null ? null : new MessageView(
                            rs.getLong("m_id"), convId, rs.getLong("m_seq"), rs.getLong("m_sender"),
                            rs.getString("m_cid"), rs.getString("m_body"), rs.getTimestamp("m_created").getTime());
                    Timestamp lastAt = rs.getTimestamp("last_message_at");
                    long updatedAt = (lastAt != null ? lastAt : rs.getTimestamp("created_at")).getTime();
                    return new ConversationView(convId, peer, rs.getLong("last_seq"), last, updatedAt);
                },
                me, me, me);
    }

    /**
     * A page of messages, oldest first. With {@code afterSeq}: the messages after that seq (used to
     * catch up). Otherwise: the newest messages before {@code beforeSeq}.
     */
    public List<MessageView> listMessages(long me, long conversationId, Long afterSeq, Long beforeSeq, int limit) {
        requireMember(me, conversationId);
        int pageSize = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        if (afterSeq != null) {
            return jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE conversation_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?",
                    MESSAGE, conversationId, afterSeq, pageSize);
        }
        List<MessageView> newestFirst = new ArrayList<>(jdbc.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE conversation_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
                MESSAGE, conversationId, beforeSeq != null ? beforeSeq : Long.MAX_VALUE, pageSize));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /**
     * Saves a message with the next seq in its conversation. If it's a retry (same clientMsgId),
     * nothing new is saved and the original message is returned.
     */
    public SendResult sendMessage(long senderId, long conversationId, UUID clientMsgId, String body) {
        validateBody(body);
        return Objects.requireNonNull(tx.execute(status -> {
            List<long[]> rows = jdbc.query("""
                    UPDATE conversations
                       SET last_seq = last_seq + 1, last_message_at = now()
                     WHERE id = ? AND (user_low = ? OR user_high = ?)
                    RETURNING last_seq, user_low, user_high""",
                    (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)},
                    conversationId, senderId, senderId);
            // Same answer for "doesn't exist" and "not yours", so conversation ids can't be probed.
            if (rows.isEmpty()) throw AppException.notFound("Conversation not found");
            long seq = rows.get(0)[0];
            long low = rows.get(0)[1];
            long high = rows.get(0)[2];

            List<MessageView> inserted = jdbc.query(
                    "INSERT INTO messages(conversation_id, seq, sender_id, client_msg_id, body) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT ON CONSTRAINT messages_idempotency_unique DO NOTHING "
                            + "RETURNING " + MESSAGE_COLUMNS,
                    MESSAGE, conversationId, seq, senderId, clientMsgId, body);
            if (!inserted.isEmpty()) return new SendResult(inserted.get(0), low, high, false);

            // A retry of a message we already stored: give back the seq number we just took...
            status.setRollbackOnly();
            // ...and answer with the original (it was committed earlier, so it's visible here).
            MessageView original = jdbc.query(
                            "SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE sender_id = ? AND client_msg_id = ?",
                            MESSAGE, senderId, clientMsgId)
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("idempotency conflict but no stored message"));
            if (original.conversationId() != conversationId) {
                throw AppException.badRequest("clientMsgId was already used for a different conversation");
            }
            return new SendResult(original, low, high, true);
        }));
    }

    private void requireMember(long me, long conversationId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE id = ? AND (user_low = ? OR user_high = ?)",
                Integer.class, conversationId, me, me);
        if (n == null || n == 0) throw AppException.notFound("Conversation not found");
    }

    /**
     * Rejects what the database would otherwise turn into a 500:
     * <ul>
     *   <li>Length counts Unicode code points. Java's String.length() (and JavaScript's) counts
     *       UTF-16 units, so "4000 characters" of emoji would be cut off at 2000.</li>
     *   <li>U+0000 is refused: Postgres TEXT can't store a NUL byte, and the insert would fail with
     *       "invalid byte sequence for encoding UTF8: 0x00".</li>
     * </ul>
     */
    static void validateBody(String body) {
        if (body == null || body.isBlank()) throw AppException.badRequest("Message can't be empty");
        if (body.codePointCount(0, body.length()) > MAX_MESSAGE_CHARS) {
            throw AppException.badRequest("Message is too long (max " + MAX_MESSAGE_CHARS + " characters)");
        }
        if (body.indexOf('\u0000') >= 0) throw AppException.badRequest("Message contains an invalid character");
    }
}
