package com.kkokkkok.backend.config;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PilotAccessFilter extends OncePerRequestFilter {
    private final boolean enabled;
    private final PilotSessionService sessions;
    // Bounded per-remote-address enrollment attempts. Behind one proxy this deliberately shares a conservative limit.
    private final Map<String, long[]> attempts = new HashMap<>();
    public PilotAccessFilter(@Value("${app.pilot.enabled:false}") boolean enabled, PilotSessionService sessions) {
        this.enabled = enabled; this.sessions = sessions;
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (!enabled || !path.startsWith("/api/")) { chain.doFilter(req, res); return; }
        res.setHeader("Cache-Control", "no-store");
        try {
            if ("/api/installations/enroll".equals(path) && "POST".equals(req.getMethod())) {
                throttle(req.getRemoteAddr());
                chain.doFilter(req, res); return;
            }
            var installation = sessions.authenticate(req.getHeader("Authorization"));
            String id = installation.getId().toString();
            String supplied = req.getHeader("X-Installation-Id");
            if (supplied != null && !id.equals(supplied))
                throw new ApiFailure(403, "INSTALLATION_MISMATCH", "등록된 기기 정보와 요청이 일치하지 않아요.");
            req.setAttribute(PilotOwnership.ATTRIBUTE, installation.getId());
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override public String getHeader(String name) {
                    return "X-Installation-Id".equalsIgnoreCase(name) ? id : super.getHeader(name);
                }
                @Override public Enumeration<String> getHeaders(String name) {
                    return "X-Installation-Id".equalsIgnoreCase(name) ? Collections.enumeration(List.of(id)) : super.getHeaders(name);
                }
                @Override public Enumeration<String> getHeaderNames() {
                    var names = new HashSet<>(Collections.list(super.getHeaderNames())); names.add("X-Installation-Id");
                    return Collections.enumeration(names);
                }
            }, res);
        } catch (ApiFailure failure) {
            res.setStatus(failure.status); res.setContentType("application/json;charset=UTF-8");
            // Filter failures have fixed messages; never include tokens or submitted invite codes.
            res.getWriter().write("{\"error\":{\"code\":\"" + failure.code + "\",\"message\":\"" + failure.getMessage() + "\"}}");
        }
    }
    private synchronized void throttle(String address) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue()[0] >= 60_000);
        if (!attempts.containsKey(address) && attempts.size() >= 4096) throw limited();
        long[] window = attempts.computeIfAbsent(address, ignored -> new long[]{now, 0});
        if (++window[1] > 10) throw limited();
    }
    private ApiFailure limited() { return new ApiFailure(429, "ENROLLMENT_RATE_LIMITED", "잠시 후 다시 등록해 주세요."); }
}
