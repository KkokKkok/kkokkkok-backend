package com.kkokkkok.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.kkokkkok.backend.domain.SensitivePlace;
import com.kkokkkok.backend.repository.SensitivePlaceRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** Naver is replaced by a loopback server; codes, tokens and member ids are synthetic. No real Naver API is called. */
@SpringBootTest(properties = {"app.auth.enabled=true", "auth.link-installation-data=true",
        "analysis.provider=stub", "editing.provider=stub", "auth.naver.client-id=synthetic-client",
        "auth.naver.client-secret=synthetic-secret", "auth.kakao.app-id=424242"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class NaverLoginApiTest {
    // Synthetic authorization code → Naver member id / nickname ("" = nickname not agreed).
    private static final Map<String, String[]> CODES = Map.of(
            "code-a", new String[]{"naver-member-a", "합성 네이버A"},
            "code-a2", new String[]{"naver-member-a", "합성 네이버A"},
            "code-b", new String[]{"naver-member-b", ""},
            "code-c", new String[]{"naver-member-c", "합성 네이버C"});
    private static final List<String> REVOKED = new CopyOnWriteArrayList<>();
    private static final List<String> SECRETS_SEEN = new CopyOnWriteArrayList<>();
    private static final HttpServer NAVER = start();
    private static final String AGREE = "{\"termsVersion\":\"2026-11-13\",\"serviceTerms\":true,\"privacy\":true,\"location\":true,\"marketing\":false}";

    @Autowired MockMvc mvc;
    @Autowired SensitivePlaceRepository places;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:naver-api;DB_CLOSE_DELAY=-1");
        r.add("assets.storage-root", () -> Path.of("build/naver-test-assets").toAbsolutePath().toString());
        String base = "http://127.0.0.1:" + NAVER.getAddress().getPort();
        r.add("auth.naver.auth-base-url", () -> base);
        r.add("auth.naver.api-base-url", () -> base);
    }

    @AfterAll static void stop() { NAVER.stop(0); }

    @Test void serverSideCodeExchangeIssuesServiceTokensWithoutExposingNaverTokens() throws Exception {
        String verifier = verifier();
        var start = call(post("/api/auth/naver/start").contentType(MediaType.APPLICATION_JSON)
                .content("{\"codeChallenge\":\"" + challenge(verifier) + "\"}"), 200);
        var authorize = URI.create(field(start, "authorizeUrl"));
        var query = query(authorize);
        assertEquals("/oauth2.0/authorize", authorize.getPath());
        assertEquals("synthetic-client", query.get("client_id"));
        assertEquals("http://127.0.0.1:8098/api/auth/naver/callback", query.get("redirect_uri"));
        assertFalse(authorize.toString().contains("synthetic-secret"));

        var page = callback("code-a", query.get("state"), null);
        assertEquals("no-store", page.getResponse().getHeader("Cache-Control"));
        String html = body(page);
        assertTrue(html.contains("intent://naver-login?code="), html);
        assertTrue(html.contains("package=com.kkokkkok.android"));
        assertFalse(html.contains("naver-access"));
        assertEquals(List.of("naver-access-code-a"), REVOKED.stream().filter(t -> t.endsWith("code-a")).toList());
        assertTrue(SECRETS_SEEN.stream().allMatch("synthetic-secret"::equals));

        String handoff = handoff(html);
        call(post("/api/auth/naver/exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + handoff + "\",\"codeVerifier\":\"" + verifier() + "\"}"), 401);
        // A failed attempt consumes the one-time code, so even the right verifier no longer works.
        call(post("/api/auth/naver/exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + handoff + "\",\"codeVerifier\":\"" + verifier + "\"}"), 401);

        var login = naverLogin("code-a2", null);
        assertTrue(field(login, "accessToken").startsWith("kka_"));
        assertEquals("naver", field(login, "provider"));
        assertEquals("합성 네이버A", field(login, "user.displayName"));
        String access = "Bearer " + field(login, "accessToken");
        call(post("/api/auth/terms").contentType(MediaType.APPLICATION_JSON).content(AGREE).header("Authorization", access), 200);
        call(get("/api/assets").header("Authorization", access), 200);
    }

    @Test void sameNaverMemberIsOneUserAndDifferentFromOthers() throws Exception {
        var first = naverLogin("code-c", null);
        var second = naverLogin("code-c", null);
        assertEquals(field(first, "user.userId"), field(second, "user.userId"));
        assertEquals(Boolean.FALSE, JsonPath.read(body(second), "$.newUser"));
        var other = naverLogin("code-b", null);
        assertNotEquals(field(first, "user.userId"), field(other, "user.userId"));
        assertEquals("꼭꼭 사용자", field(other, "user.displayName"));
    }

    @Test void firstNaverLoginLinksPlacesSavedUnderThisInstallation() throws Exception {
        UUID installation = UUID.randomUUID();
        places.saveAndFlush(new SensitivePlace(UUID.randomUUID(), installation, "합성 집", "home", 37.5, 127.0, 300));
        var login = naverLogin("code-b", installation);
        assertEquals(Boolean.TRUE, JsonPath.read(body(login), "$.installationDataLinked"));
    }

    @Test void rejectsUnknownExpiredCancelledAndMalformedRequests() throws Exception {
        assertTrue(body(callback("code-a", "unknown-state", null)).contains("error=expired"));
        String state = state(challenge(verifier()));
        assertTrue(body(callback(null, state, "access_denied")).contains("error=cancelled"));
        // The state is single use: replaying it after the cancel does not log in.
        assertTrue(body(callback("code-a", state, null)).contains("error=expired"));
        assertTrue(body(callback("bad-code", state(challenge(verifier())), null)).contains("error=failed"));
        call(post("/api/auth/naver/start").contentType(MediaType.APPLICATION_JSON).content("{\"codeChallenge\":\"short\"}"), 400);
        call(post("/api/auth/naver/exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"nope\",\"codeVerifier\":\"" + verifier() + "\"}"), 401);
    }

    // --- helpers ---

    private MvcResult naverLogin(String code, UUID installation) throws Exception {
        String verifier = verifier();
        var start = call(post("/api/auth/naver/start").contentType(MediaType.APPLICATION_JSON)
                .content("{\"codeChallenge\":\"" + challenge(verifier) + "\""
                        + (installation == null ? "" : ",\"installationId\":\"" + installation + "\"") + "}"), 200);
        String state = query(URI.create(field(start, "authorizeUrl"))).get("state");
        String handoff = handoff(body(callback(code, state, null)));
        return call(post("/api/auth/naver/exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + handoff + "\",\"codeVerifier\":\"" + verifier + "\"}"), 200);
    }

    private String state(String challenge) throws Exception {
        var start = call(post("/api/auth/naver/start").contentType(MediaType.APPLICATION_JSON)
                .content("{\"codeChallenge\":\"" + challenge + "\"}"), 200);
        return query(URI.create(field(start, "authorizeUrl"))).get("state");
    }

    private MvcResult callback(String code, String state, String error) throws Exception {
        var request = get("/api/auth/naver/callback");
        if (code != null) request.param("code", code);
        if (state != null) request.param("state", state);
        if (error != null) request.param("error", error);
        return call(request, 200);
    }

    private static String handoff(String html) {
        var matcher = Pattern.compile("kkokkkok://naver-login\\?code=([A-Za-z0-9_-]+)").matcher(html);
        assertTrue(matcher.find(), html);
        return matcher.group(1);
    }

    private static String verifier() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static Map<String, String> query(URI uri) {
        var map = new HashMap<String, String>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return map;
    }

    private MvcResult call(AbstractMockHttpServletRequestBuilder<?> request, int expected) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertEquals(expected, result.getResponse().getStatus(), () -> safeBody(result));
        return result;
    }

    private static String safeBody(MvcResult result) {
        try { return body(result); } catch (Exception e) { return ""; }
    }

    private static String body(MvcResult result) throws Exception { return result.getResponse().getContentAsString(StandardCharsets.UTF_8); }
    private static String field(MvcResult result, String path) throws Exception { return String.valueOf((Object) JsonPath.read(body(result), "$." + path)); }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                String json;
                if (path.equals("/oauth2.0/token")) {
                    var form = new HashMap<String, String>();
                    for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                        String[] kv = pair.split("=", 2);
                        if (kv.length == 2) form.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                    }
                    SECRETS_SEEN.add(form.get("client_secret"));
                    if ("delete".equals(form.get("grant_type"))) {
                        REVOKED.add(form.get("access_token"));
                        json = "{\"access_token\":\"" + form.get("access_token") + "\",\"result\":\"success\"}";
                    } else if (CODES.containsKey(form.get("code")) && "synthetic-client".equals(form.get("client_id")))
                        json = "{\"access_token\":\"naver-access-" + form.get("code") + "\",\"token_type\":\"bearer\",\"expires_in\":\"3600\"}";
                    else json = "{\"error\":\"invalid_request\",\"error_description\":\"no valid data in session\"}";
                } else if (path.equals("/v1/nid/me")) {
                    String auth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
                    var member = auth.startsWith("Bearer naver-access-") ? CODES.get(auth.substring("Bearer naver-access-".length())) : null;
                    if (member == null) { status = 401; json = "{\"resultcode\":\"024\",\"message\":\"Authentication failed\"}"; }
                    else json = "{\"resultcode\":\"00\",\"message\":\"success\",\"response\":{\"id\":\"" + member[0] + "\""
                            + (member[1].isEmpty() ? "" : ",\"nickname\":\"" + member[1] + "\"") + "}}";
                } else { status = 404; json = "{}"; }
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
