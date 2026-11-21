package com.chatapp.realtime;

import com.chatapp.auth.User;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/** Uses the user id as the WebSocket user name, so the server can send to a user by id. */
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
