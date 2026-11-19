package com.chatapp.realtime;

import com.chatapp.auth.AuthService;
import com.chatapp.auth.SessionCookies;
import com.chatapp.config.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket at /ws, with Spring's in-memory ("simple") broker.
 *
 * <p>Destinations used (everything else is refused by {@link StompDestinationGuard}):
 * <pre>
 *   client SEND  /app/hello            -> server replies on /user/queue/ready (subscriptions are live)
 *   client SEND  /app/send             -> server replies on /user/queue/acks or /user/queue/errors
 *   server push  /user/queue/messages  -> new message in one of my conversations (every tab)
 * </pre>
 *
 * <p>Spring handles the WebSocket protocol, STOMP framing and routing to sessions. What it does NOT
 * give you, and what this app adds on top: authentication on the handshake, per-conversation
 * ordering, idempotent retries, persistence before delivery, catch-up after a reconnect, and
 * stopping clients from subscribing to or sending into other users' queues.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    /** Both directions: the broker pings every 10 s, and expects the browser to do the same. */
    private static final long HEARTBEAT_MS = 10_000;

    private final AuthService auth;
    private final SessionCookies cookies;
    private final AppProperties props;
    private final StompDestinationGuard guard;
    private final LiveSessionRegistry liveSessions;

    public WebSocketConfig(AuthService auth, SessionCookies cookies, AppProperties props,
                           StompDestinationGuard guard, LiveSessionRegistry liveSessions) {
        this.auth = auth;
        this.cookies = cookies;
        this.props = props;
        this.guard = guard;
        this.liveSessions = liveSessions;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Plain WebSocket, no SockJS fallback: every current browser supports WebSocket, and SockJS
        // adds HTTP-streaming transports that complicate proxies and auth for no benefit here.
        StompWebSocketEndpointRegistration endpoint = registry.addEndpoint("/ws")
                .addInterceptors(new SessionHandshakeInterceptor(auth, cookies))
                .setHandshakeHandler(new UserIdHandshakeHandler());
        // Cross-site WebSocket hijacking: WebSockets aren't covered by CORS, so without an Origin
        // check any website could open a socket that carries our user's cookie. Spring allows
        // same-origin only unless origins are listed explicitly.
        if (!props.allowedOrigins().isEmpty()) {
            endpoint.setAllowedOriginPatterns(props.allowedOrigins().toArray(String[]::new));
        }
        // The inbound channel is a thread pool, so two frames from one browser can be handled in
        // parallel and finish out of order: two quick sends could be stored in reverse order, or the
        // "hello" handled before the SUBSCRIBE frames sent ahead of it. This makes Spring process
        // each session's frames one at a time, in arrival order. Our "ready" handshake relies on it.
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler());
        // Same problem on the way out: without this, messages to one session can overtake each
        // other in the outbound thread pool (the client would see #8 before #7).
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(guard);
    }

    /**
     * Slow consumers: if a browser stops reading (backgrounded phone, dead network path), Spring
     * buffers outgoing messages for it. These limits disconnect it instead of letting the buffer
     * grow without bound. That's safe because every message is in Postgres: when the client
     * reconnects it fetches everything after its last seq.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(64 * 1024)        // a 4000-character message is at most ~16 KB of UTF-8
                .setSendTimeLimit(10_000)
                .setSendBufferSizeLimit(512 * 1024)
                .addDecoratorFactory(liveSessions::decorate);
    }

    /**
     * Heartbeats detect half-open connections: a laptop that went to sleep or a phone that changed
     * networks never sends a TCP FIN, so without traffic neither side notices for a long time.
     * 10 s also stays well under common proxy idle timeouts (nginx and AWS ALB: 60 s).
     */
    private static ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
