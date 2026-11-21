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
 * WebSocket setup: STOMP at /ws with Spring's in-memory broker.
 * Clients send to /app/hello and /app/send, and listen on /user/queue/ready, acks, errors and messages.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    /** Heartbeat interval in both directions, used to detect dead connections. */
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
        // Plain WebSocket, no SockJS fallback.
        StompWebSocketEndpointRegistration endpoint = registry.addEndpoint("/ws")
                .addInterceptors(new SessionHandshakeInterceptor(auth, cookies))
                .setHandshakeHandler(new UserIdHandshakeHandler());
        // Only our own site can open a WebSocket, unless other origins are configured.
        if (!props.allowedOrigins().isEmpty()) {
            endpoint.setAllowedOriginPatterns(props.allowedOrigins().toArray(String[]::new));
        }
        // Handle each client's frames one at a time, in the order they arrive.
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler());
        // Send to each client in order too.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(guard);
    }

    /** Size and buffer limits. A client that stops reading is disconnected and catches up later. */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(64 * 1024)
                .setSendTimeLimit(10_000)
                .setSendBufferSizeLimit(512 * 1024)
                .addDecoratorFactory(liveSessions::decorate);
    }

    /** Thread that sends and checks the heartbeats. */
    private static ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
