package com.kkokkkok.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Single-user pilot access only. Replace with authenticated user ownership before multi-user deployment. */
@Component
public class AccessFilter extends OncePerRequestFilter {
    private final String key;
    private final boolean apiKeyRequired;
    public AccessFilter(@Value("${app.api-key:}") String key,
                        @Value("${app.api-key-required:true}") boolean apiKeyRequired) {
        this.key = key;
        this.apiKeyRequired = apiKeyRequired;
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        if (!req.getServletPath().startsWith("/api/")) { chain.doFilter(req,res); return; }
        String provided = req.getHeader("X-App-Api-Key");
        if (apiKeyRequired && (key.isBlank() || provided == null || !MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))) {
            res.setStatus(key.isBlank()?503:401); res.setContentType("application/json");
            res.getWriter().write("{\"error\":{\"code\":\"ACCESS_DENIED\",\"message\":\"API access key required\"}}"); return;
        }
        res.setHeader("Cache-Control","no-store");
        chain.doFilter(req,res);
    }
}
