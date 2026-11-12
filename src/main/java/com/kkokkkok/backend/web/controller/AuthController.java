package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.config.AuthAccessFilter;
import com.kkokkkok.backend.service.AuthService;
import com.kkokkkok.backend.service.PilotOwnership;
import com.kkokkkok.backend.web.dto.KakaoLoginRequest;
import com.kkokkkok.backend.web.dto.RefreshTokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final PilotOwnership ownership;
    public AuthController(AuthService auth, PilotOwnership ownership) { this.auth = auth; this.ownership = ownership; }

    @PostMapping("/kakao")
    public Map<String, Object> kakao(@Valid @RequestBody KakaoLoginRequest request) {
        return auth.loginWithKakao(request.accessToken(), request.installationId());
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout((UUID) request.getAttribute(AuthAccessFilter.SESSION_ATTRIBUTE));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me() { return auth.me(ownership.current()); }
}
