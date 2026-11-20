package com.chatapp.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * Checks the session cookie on API requests and returns 401 if there's no valid session.
 * Controllers get the signed-in user with {@code @RequestAttribute(CURRENT_USER)}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String CURRENT_USER = "currentUser";

    private final AuthService auth;
    private final SessionCookies cookies;

    public AuthInterceptor(AuthService auth, SessionCookies cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Optional<AuthService.AuthenticatedSession> session = cookies.read(request).flatMap(auth::authenticate);
        if (session.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":{\"code\":\"unauthorized\",\"message\":\"Not signed in\"}}");
            return false;
        }
        request.setAttribute(CURRENT_USER, session.get().user());
        return true;
    }
}
