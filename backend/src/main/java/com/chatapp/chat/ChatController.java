package com.chatapp.chat;

import com.chatapp.auth.AuthInterceptor;
import com.chatapp.auth.AuthService;
import com.chatapp.auth.User;
import com.chatapp.common.AppException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoints for the conversation list, starting a chat and loading messages.
 * Sending messages goes over the WebSocket (see ChatMessagingController).
 */
@RestController
@RequestMapping("/api/conversations")
public class ChatController {
    public record StartRequest(String email) {}

    private final ChatService chat;
    private final AuthService auth;

    public ChatController(ChatService chat, AuthService auth) {
        this.chat = chat;
        this.auth = auth;
    }

    @GetMapping
    public Map<String, Object> list(@RequestAttribute(AuthInterceptor.CURRENT_USER) User me) {
        return Map.of("conversations", chat.listConversations(me.id()));
    }

    /** Starts (or reopens) a chat with the user who has this email. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> start(@RequestAttribute(AuthInterceptor.CURRENT_USER) User me, @RequestBody StartRequest req) {
        if (req.email() == null || req.email().isBlank()) throw AppException.badRequest("Email is required");
        User other = auth.findByEmail(req.email())
                .orElseThrow(() -> AppException.notFound("No user with that email. Ask them to sign up first."));
        return Map.of("conversation", chat.startConversation(me, other));
    }

    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(@RequestAttribute(AuthInterceptor.CURRENT_USER) User me,
                                        @PathVariable("id") long conversationId,
                                        @RequestParam(name = "after", required = false) Long after,
                                        @RequestParam(name = "before", required = false) Long before,
                                        @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return Map.of("messages", chat.listMessages(me.id(), conversationId, after, before, limit));
    }
}
