package com.kkokkkok.backend.config;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.service.AuthService;
import com.kkokkkok.backend.service.PilotOwnership;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Login mode (app.auth.enabled). Every /api request except login/refresh needs a service access token.
 * The authenticated user id becomes the data owner: it is exposed through {@link PilotOwnership} and replaces
 * any client-supplied X-Installation-Id so installation-scoped endpoints are scoped to the user.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthAccessFilter extends OncePerRequestFilter {
    public static final String SESSION_ATTRIBUTE = "auth.session-id";
    private static final Set<String> PUBLIC = Set.of("POST /api/auth/kakao", "POST /api/auth/refresh");
    private final boolean enabled;
    private final AuthService auth;

    public AuthAccessFilter(@Value("${app.auth.enabled:false}") boolean enabled,
                            @Value("${app.pilot.enabled:false}") boolean pilotEnabled, AuthService auth) {
        if (enabled && pilotEnabled) throw new IllegalStateException("app.auth.enabled and app.pilot.enabled cannot both be true");
        this.enabled = enabled; this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (!path.startsWith("/api/")) { chain.doFilter(req, res); return; }
        if (!enabled) {
            if (path.startsWith("/api/auth/")) {
                write(res, new ApiFailure(404, "AUTH_DISABLED", "이 서버에서는 로그인을 사용하지 않아요."));
                return;
            }
            chain.doFilter(req, res); return;
        }
        res.setHeader("Cache-Control", "no-store");
        if (PUBLIC.contains(req.getMethod() + " " + path)) { chain.doFilter(req, res); return; }
        try {
            var session = auth.authenticate(req.getHeader("Authorization"));
            String owner = session.getUserId().toString();
            req.setAttribute(PilotOwnership.ATTRIBUTE, session.getUserId());
            req.setAttribute(SESSION_ATTRIBUTE, session.getId());
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override public String getHeader(String name) {
                    return "X-Installation-Id".equalsIgnoreCase(name) ? owner : super.getHeader(name);
                }
                @Override public Enumeration<String> getHeaders(String name) {
                    return "X-Installation-Id".equalsIgnoreCase(name) ? Collections.enumeration(List.of(owner)) : super.getHeaders(name);
                }
                @Override public Enumeration<String> getHeaderNames() {
                    var names = new HashSet<>(Collections.list(super.getHeaderNames()));
                    names.add("X-Installation-Id");
                    return Collections.enumeration(names);
                }
            }, res);
        } catch (ApiFailure failure) {
            write(res, failure);
        }
    }

    private static void write(HttpServletResponse res, ApiFailure failure) throws IOException {
        res.setStatus(failure.status);
        res.setContentType("application/json;charset=UTF-8");
        // Fixed messages only; never echo tokens.
        res.getWriter().write("{\"error\":{\"code\":\"" + failure.code + "\",\"message\":\"" + failure.getMessage() + "\"}}");
    }
}
