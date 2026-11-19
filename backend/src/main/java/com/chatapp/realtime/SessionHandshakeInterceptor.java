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

/**
 * Authenticates the WebSocket handshake with the same session cookie as the REST API.
 *
 * <p>The browser WebSocket API can't set headers, but it does send cookies on the upgrade
 * request. Checking here, before the connection is upgraded, means an unauthenticated client gets
 * a plain HTTP 401 and never a socket. That's simpler and cheaper than accepting the connection
 * and validating a token inside the STOMP CONNECT frame.
 */
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
        // Copied into the WebSocket session: used for the STOMP Principal and to close this
        // socket if the same login session logs out elsewhere.
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
