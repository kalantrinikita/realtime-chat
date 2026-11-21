package com.chatapp.realtime;

import com.chatapp.auth.AuthService;
import com.chatapp.auth.SessionCookies;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/** Checks the session cookie before a WebSocket opens. Without a valid session it returns 401. */
final class SessionHandshakeInterceptor implements HandshakeInterceptor {
    static final String USER = "chat.user";
    static final String TOKEN_HASH = "chat.tokenHash";

    private final AuthService auth;
    private final SessionCookies cookies;

    SessionHandshakeInterceptor(AuthService auth, SessionCookies cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        Optional<AuthService.AuthenticatedSession> session =
                cookies.read(servletRequest.getServletRequest()).flatMap(auth::authenticate);
        if (session.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        // Kept on the WebSocket session for later use.
        attributes.put(USER, session.get().user());
        attributes.put(TOKEN_HASH, session.get().tokenHash());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }
}
