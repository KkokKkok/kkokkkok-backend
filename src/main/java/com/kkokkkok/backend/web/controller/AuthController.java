package com.kkokkkok.backend.web.controller;

import com.kkokkkok.backend.config.AuthAccessFilter;
import com.kkokkkok.backend.service.AuthService;
import com.kkokkkok.backend.service.NaverLoginService;
import com.kkokkkok.backend.service.PilotOwnership;
import com.kkokkkok.backend.service.TermsService;
import com.kkokkkok.backend.web.dto.TermsAgreementRequest;
import com.kkokkkok.backend.web.dto.KakaoLoginRequest;
import com.kkokkkok.backend.web.dto.NaverLoginExchangeRequest;
import com.kkokkkok.backend.web.dto.NaverLoginStartRequest;
import com.kkokkkok.backend.web.dto.RefreshTokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final PilotOwnership ownership;
    private final TermsService terms;
    private final NaverLoginService naver;
    public AuthController(AuthService auth, PilotOwnership ownership, TermsService terms, NaverLoginService naver) {
        this.auth = auth; this.ownership = ownership; this.terms = terms; this.naver = naver;
    }

    @GetMapping("/terms")
    public Map<String, Object> termsStatus() { return terms.status(ownership.current()); }

    @PostMapping("/terms")
    public Map<String, Object> agree(@Valid @RequestBody TermsAgreementRequest request) {
        return terms.agree(ownership.current(), request);
    }

    @PostMapping("/kakao")
    public Map<String, Object> kakao(@Valid @RequestBody KakaoLoginRequest request) {
        return auth.loginWithKakao(request.accessToken(), request.installationId());
    }

    @PostMapping("/naver/start")
    public Map<String, Object> naverStart(@Valid @RequestBody NaverLoginStartRequest request) {
        return naver.start(request.codeChallenge(), request.installationId());
    }

    /** Naver redirects the browser here. The page sends the user back to the app with a one-time code (no tokens). */
    @GetMapping("/naver/callback")
    public ResponseEntity<String> naverCallback(@RequestParam(required = false) String code,
                                                @RequestParam(required = false) String state,
                                                @RequestParam(required = false) String error) {
        var target = naver.callback(code, state, error);
        String intent = HtmlUtils.htmlEscape(target.intentLink()), app = HtmlUtils.htmlEscape(target.appLink());
        String message = target.success() ? "로그인을 마쳤어요. 꼭꼭 앱으로 돌아갈게요." : "로그인을 마치지 못했어요. 꼭꼭 앱으로 돌아가 다시 시도해 주세요.";
        String html = "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>꼭꼭</title>"
                + "<style>body{font-family:sans-serif;text-align:center;padding:48px 24px;color:#3b2a20;background:#fdf8f4}"
                + "a{display:inline-block;margin-top:24px;padding:14px 28px;border-radius:999px;background:#ff9a3c;color:#fff;text-decoration:none;font-weight:bold}</style>"
                + "</head><body><p>" + message + "</p><a id=\"back\" href=\"" + intent + "\">꼭꼭으로 돌아가기</a>"
                + "<p><a href=\"" + app + "\" style=\"background:none;color:#8a7a70;font-weight:normal\">앱이 열리지 않으면 여기를 눌러 주세요</a></p>"
                + "<script>location.replace(document.getElementById('back').href)</script></body></html>";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'")
                .body(html);
    }

    @PostMapping("/naver/exchange")
    public Map<String, Object> naverExchange(@Valid @RequestBody NaverLoginExchangeRequest request) {
        return naver.exchange(request.code(), request.codeVerifier());
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
