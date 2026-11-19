package com.chatapp.realtime;

import com.chatapp.auth.User;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * Makes the user id the STOMP Principal name. That is what lets the server address a person rather
 * than a socket: {@code convertAndSendToUser("42", "/queue/messages", ...)} reaches every tab and
 * device user 42 has open.
 */
final class UserIdHandshakeHandler extends DefaultHandshakeHandler {

    record UserPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        User user = (User) attributes.get(SessionHandshakeInterceptor.USER);
        return new UserPrincipal(String.valueOf(user.id()));
    }
}
