package com.kkokkkok.backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Single backend instance: deletion never overlaps a request that can create or refresh records. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class DataDeletionFilter extends OncePerRequestFilter {
    private final ReentrantReadWriteLock gate=new ReentrantReadWriteLock(true);
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        String path=req.getRequestURI().substring(req.getContextPath().length());
        if(!path.startsWith("/api/")) { chain.doFilter(req,res); return; }
        boolean deletion="DELETE".equals(req.getMethod()) && "/api/profile/data".equals(path);
        var lock=deletion ? gate.writeLock() : gate.readLock();
        if(!lock.tryLock()) {
            res.setStatus(409); res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":{\"code\":\"DATA_BUSY\",\"message\":\"진행 중인 요청이 있어요. 잠시 후 다시 시도해 주세요.\"}}");
            return;
        }
        try { chain.doFilter(req,res); } finally { lock.unlock(); }
    }
}
