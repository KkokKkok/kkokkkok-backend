package com.kkokkkok.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.kkokkkok.backend.domain.SensitivePlace;
import com.kkokkkok.backend.repository.SensitivePlaceRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** Kakao is replaced by a loopback server; tokens below are synthetic and no real Kakao API is called. */
@SpringBootTest(properties = {"app.auth.enabled=true", "auth.link-installation-data=true",
        "analysis.provider=stub", "editing.provider=stub"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AuthApiTest {
    private static final String APP_ID = "424242";
    // Synthetic Kakao tokens → Kakao member ids; "other-app" belongs to a different Kakao app.
    private static final Map<String, String[]> KAKAO_TOKENS = Map.of(
            "kakao-token-a", new String[]{"1001", APP_ID, "합성 사용자A"},
            "kakao-token-b", new String[]{"1002", APP_ID, "합성 사용자B"},
            "kakao-token-c", new String[]{"1003", APP_ID, "합성 사용자C"},
            "kakao-token-d-no-consent", new String[]{"1004", APP_ID, ""},
            "kakao-token-d", new String[]{"1004", APP_ID, "합성 사용자D"},
            "kakao-token-e", new String[]{"1005", APP_ID, "합성 사용자E"},
            "kakao-token-other-app", new String[]{"1001", "999", "다른 앱"});
    private static final AtomicInteger KAKAO_CALLS = new AtomicInteger();
    private static final HttpServer KAKAO = start();

    @Autowired MockMvc mvc;
    @Autowired SensitivePlaceRepository places;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:auth-api;DB_CLOSE_DELAY=-1");
        r.add("assets.storage-root", () -> Path.of("build/auth-test-assets").toAbsolutePath().toString());
        r.add("auth.kakao.base-url", () -> "http://127.0.0.1:" + KAKAO.getAddress().getPort());
        r.add("auth.kakao.app-id", () -> APP_ID);
    }

    @AfterAll static void stop() { KAKAO.stop(0); }

    @Test void loginCreatesUserOnceAndReturnsServiceTokens() throws Exception {
        var first = login("kakao-token-a", null, 200);
        assertTrue(field(first, "accessToken").startsWith("kka_"));
        assertTrue(field(first, "refreshToken").startsWith("kkr_"));
        String body = first.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("kakao-token-a"));
        assertEquals("합성 사용자A", field(first, "user.displayName"));
        var second = login("kakao-token-a", null, 200);
        assertEquals(field(first, "user.userId"), field(second, "user.userId"));
        assertEquals(Boolean.FALSE, JsonPath.read(body(second), "$.newUser"));
        String access = "Bearer " + field(second, "accessToken");
        assertEquals("합성 사용자A", field(call(get("/api/auth/me"), access, 200), "displayName"));
    }

    @Test void rejectsInvalidOrForeignAppTokensAndUnauthenticatedRequests() throws Exception {
        login("kakao-token-unknown", null, 401);
        login("kakao-token-other-app", null, 401);
        call(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON).content("{\"accessToken\":\"\"}"), null, 400);
        call(get("/api/assets"), null, 401);
        call(get("/api/assets"), "Bearer kka_" + "A".repeat(43), 401);
        call(get("/api/places"), "Bearer not-a-token", 401);
    }

    @Test void refreshRotatesTokensAndReuseRevokesSession() throws Exception {
        signedUp("kakao-token-a");
        var login = login("kakao-token-a", null, 200);
        String oldAccess = "Bearer " + field(login, "accessToken"), oldRefresh = field(login, "refreshToken");
        var refreshed = refresh(oldRefresh, 200);
        String newAccess = "Bearer " + field(refreshed, "accessToken"), newRefresh = field(refreshed, "refreshToken");
        assertNotEquals(oldRefresh, newRefresh);
        call(get("/api/assets"), oldAccess, 401);
        call(get("/api/assets"), newAccess, 200);
        // Replaying the rotated-out refresh token ends the session entirely.
        refresh(oldRefresh, 401);
        call(get("/api/assets"), newAccess, 401);
        refresh(newRefresh, 401);
    }

    @Test void logoutRevokesAccessAndRefresh() throws Exception {
        var login = login("kakao-token-a", null, 200);
        String access = "Bearer " + field(login, "accessToken");
        call(post("/api/auth/logout"), access, 204);
        call(get("/api/assets"), access, 401);
        refresh(field(login, "refreshToken"), 401);
    }

    @Test void usersCannotReadChangeOrDeleteEachOthersData() throws Exception {
        String a = signedUp("kakao-token-a");
        String b = signedUp("kakao-token-b");
        String asset = field(call(multipart("/api/assets").file(new MockMultipartFile("photo", "synthetic.jpg", "image/jpeg",
                new byte[]{(byte) 255, (byte) 216, (byte) 255, 0})), a, 201), "assetId");
        String inspection = field(call(post("/api/inspections").header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":\"" + asset + "\"}"), a, 202), "inspectionId");
        call(get("/api/inspections/" + inspection), a, 200);
        // Owner can read the photo bytes after login.
        var content = call(get("/api/assets/" + asset + "/content"), a, 200);
        assertEquals(4, content.getResponse().getContentAsByteArray().length);

        for (String suffix : List.of("", "/content")) call(get("/api/assets/" + asset + suffix), b, 404);
        call(put("/api/assets/" + asset + "/capture-location").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":37.5,\"longitude\":127,\"source\":\"manual\"}"), b, 404);
        call(delete("/api/assets/" + asset), b, 404);
        call(get("/api/inspections/" + inspection), b, 404);
        call(get("/api/inspections").param("assetId", asset), b, 404);
        assertEquals(0, ((List<?>) JsonPath.read(body(call(get("/api/assets"), b, 200)), "$")).size());

        // Places are scoped to the user even if a client sends someone else's installation id.
        String place = field(call(post("/api/places").header("X-Installation-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"합성 장소\",\"category\":\"home\",\"latitude\":37.5,\"longitude\":127.0}"), a, 201), "placeId");
        call(get("/api/places/" + place), b, 404);
        call(delete("/api/places/" + place), b, 404);
        assertEquals(0, ((List<?>) JsonPath.read(body(call(get("/api/places"), b, 200)), "$")).size());
        assertEquals(1, ((List<?>) JsonPath.read(body(call(get("/api/places"), a, 200)), "$")).size());
        assertEquals(0, ((Number) JsonPath.read(body(call(get("/api/profile"), b, 200)), "$.photoChecks")).intValue());
        assertEquals("user", JsonPath.read(body(call(get("/api/profile"), a, 200)), "$.dataScope"));
        call(get("/api/assets/" + asset), a, 200);
    }

    @Test void ownerSeesPlaceCoordinatesAndAddressOnAnyDevice() throws Exception {
        String a = signedUp("kakao-token-e");
        var created = call(post("/api/places").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"합성 학교\",\"category\":\"school\",\"latitude\":37.51,\"longitude\":127.02,"
                        + "\"radiusMeters\":500,\"address\":\" 합성시 합성로 1 \"}"), a, 201);
        assertEquals("no-store", created.getResponse().getHeader("Cache-Control"));
        String place = field(created, "placeId");
        // Another session of the same account (e.g. a second phone) gets the same point and label back.
        String second = "Bearer " + field(login("kakao-token-e", UUID.randomUUID(), 200), "accessToken");
        var detail = call(get("/api/places/" + place), second, 200);
        assertEquals(37.51, ((Number) JsonPath.read(body(detail), "$.latitude")).doubleValue());
        assertEquals(127.02, ((Number) JsonPath.read(body(detail), "$.longitude")).doubleValue());
        assertEquals("합성시 합성로 1", field(detail, "address"));
        assertEquals("no-store", detail.getResponse().getHeader("Cache-Control"));
        var updated = call(put("/api/places/" + place).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"합성 학교\",\"category\":\"school\",\"latitude\":37.52,\"longitude\":127.03}"), second, 200);
        assertEquals("null", field(updated, "address"));
        assertEquals(List.of(37.52), JsonPath.read(body(call(get("/api/places"), a, 200)), "$[?(@.placeId=='" + place + "')].latitude"));
        call(post("/api/places").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"긴 주소\",\"category\":\"home\",\"latitude\":37.5,\"longitude\":127.0,\"address\":\""
                        + "가".repeat(201) + "\"}"), a, 400);
    }

    @Test void firstLoginLinksPlacesSavedUnderThisInstallation() throws Exception {
        UUID installation = UUID.randomUUID(), otherInstallation = UUID.randomUUID();
        places.saveAndFlush(new SensitivePlace(UUID.randomUUID(), installation, "합성 집", "home", 37.5, 127.0, 300));
        places.saveAndFlush(new SensitivePlace(UUID.randomUUID(), otherInstallation, "다른 기기 장소", "home", 37.6, 127.1, 300));
        var login = login("kakao-token-c", installation, 200);
        assertEquals(Boolean.TRUE, JsonPath.read(body(login), "$.installationDataLinked"));
        String access = "Bearer " + field(login, "accessToken");
        call(post("/api/auth/terms").contentType(MediaType.APPLICATION_JSON).content(AGREE), access, 200);
        var list = body(call(get("/api/places"), access, 200));
        assertEquals(List.of("합성 집"), JsonPath.read(list, "$[*].name"));
    }

    @Test void dataApisRequireServerSideTermsConsent() throws Exception {
        var login = login("kakao-token-b", null, 200);
        String access = "Bearer " + field(login, "accessToken");
        call(get("/api/assets"), access, 403);
        assertEquals("TERMS_REQUIRED", JsonPath.read(body(call(get("/api/places"), access, 403)), "$.error.code"));
        assertEquals(Boolean.FALSE, JsonPath.read(body(call(get("/api/auth/terms"), access, 200)), "$.accepted"));
        call(post("/api/auth/terms").contentType(MediaType.APPLICATION_JSON)
                .content(AGREE.replace("\"location\":true", "\"location\":false")), access, 400);
        call(post("/api/auth/terms").contentType(MediaType.APPLICATION_JSON)
                .content(AGREE.replace("2026-11-13", "2020-01-01")), access, 409);
        var agreed = call(post("/api/auth/terms").contentType(MediaType.APPLICATION_JSON)
                .content(AGREE.replace("\"marketing\":false", "\"marketing\":true")), access, 200);
        assertEquals(Boolean.TRUE, JsonPath.read(body(agreed), "$.accepted"));
        assertEquals(Boolean.TRUE, JsonPath.read(body(agreed), "$.marketing"));
        call(get("/api/assets"), access, 200);
        assertEquals(Boolean.TRUE, JsonPath.read(body(login("kakao-token-b", null, 200)), "$.termsAccepted"));
        assertEquals(Boolean.TRUE, JsonPath.read(body(call(get("/api/auth/me"), access, 200)), "$.termsAccepted"));
    }

    @Test void missingNicknameConsentIsReportedAndLaterConsentUpdatesName() throws Exception {
        var first = login("kakao-token-d-no-consent", null, 200);
        assertEquals(Boolean.TRUE, JsonPath.read(body(first), "$.profileConsentRequired"));
        assertEquals("꼭꼭 사용자", field(first, "user.displayName"));
        // After additional consent the app logs in again with the new Kakao token: same user, real nickname.
        var second = login("kakao-token-d", null, 200);
        assertEquals(Boolean.FALSE, JsonPath.read(body(second), "$.profileConsentRequired"));
        assertEquals(field(first, "user.userId"), field(second, "user.userId"));
        assertEquals("합성 사용자D", field(second, "user.displayName"));
    }

    @Test void kakaoIsNotCalledForMalformedRefreshOrUnauthenticatedCalls() throws Exception {
        int before = KAKAO_CALLS.get();
        refresh("kkr_short", 401);
        call(get("/api/profile"), null, 401);
        assertEquals(before, KAKAO_CALLS.get());
    }

    private static final String AGREE = "{\"termsVersion\":\"2026-11-13\",\"serviceTerms\":true,\"privacy\":true,\"location\":true,\"marketing\":false}";

    /** Logs in and agrees to the current terms; returns the Authorization header value. */
    private String signedUp(String kakaoToken) throws Exception {
        String access = "Bearer " + field(login(kakaoToken, null, 200), "accessToken");
        call(post("/api/auth/terms").contentType(MediaType.APPLICATION_JSON).content(AGREE), access, 200);
        return access;
    }

    private MvcResult login(String kakaoToken, UUID installation, int expected) throws Exception {
        String json = "{\"accessToken\":\"" + kakaoToken + "\"" + (installation == null ? "" : ",\"installationId\":\"" + installation + "\"") + "}";
        return call(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(json), null, expected);
    }

    private MvcResult refresh(String token, int expected) throws Exception {
        return call(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + token + "\"}"), null, expected);
    }

    private MvcResult call(AbstractMockHttpServletRequestBuilder<?> request, String authorization, int expected) throws Exception {
        if (authorization != null) request.header("Authorization", authorization);
        var result = mvc.perform(request).andReturn();
        assertEquals(expected, result.getResponse().getStatus(), () -> safeBody(result));
        return result;
    }

    private static String safeBody(MvcResult result) {
        try { return result.getResponse().getContentAsString(StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
    }

    private static String body(MvcResult result) throws Exception { return result.getResponse().getContentAsString(StandardCharsets.UTF_8); }
    private static String field(MvcResult result, String path) throws Exception { return String.valueOf((Object) JsonPath.read(body(result), "$." + path)); }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                KAKAO_CALLS.incrementAndGet();
                String auth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
                var user = auth.startsWith("Bearer ") ? KAKAO_TOKENS.get(auth.substring(7)) : null;
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                String json;
                if (user == null) { status = 401; json = "{\"msg\":\"this access token does not exist\",\"code\":-401}"; }
                else if (path.equals("/v1/user/access_token_info"))
                    json = "{\"id\":" + user[0] + ",\"expires_in\":7199,\"app_id\":" + user[1] + "}";
                else if (path.equals("/v2/user/me"))
                    json = user[2].isEmpty()
                            ? "{\"id\":" + user[0] + ",\"kakao_account\":{\"profile_nickname_needs_agreement\":true}}"
                            : "{\"id\":" + user[0] + ",\"connected_at\":\"" + Instant.now() + "\",\"kakao_account\":{\"profile_nickname_needs_agreement\":false,\"profile\":{\"nickname\":\"" + user[2] + "\"}}}";
                else { status = 404; json = "{}"; }
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
