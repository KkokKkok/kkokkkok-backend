package com.kkokkkok.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Uses a loopback stand-in for Kakao Local; no real Kakao API is called. */
@SpringBootTest
@AutoConfigureMockMvc
class GeocodingApiTest {
    private static final String SYNTHETIC_KEY = "synthetic-kakao-rest-key";
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static volatile int forcedStatus = 200;
    private static final HttpServer KAKAO = start();

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> "test-key");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:geocoding-test;DB_CLOSE_DELAY=-1");
        registry.add("assets.storage-root", () -> "build/geocoding-test-assets");
        registry.add("kakao.local.base-url", () -> "http://127.0.0.1:" + KAKAO.getAddress().getPort());
        registry.add("kakao.local.rest-api-key", () -> SYNTHETIC_KEY);
    }

    @BeforeEach void reset() { REQUESTS.clear(); forcedStatus = 200; }
    @AfterAll static void stop() { KAKAO.stop(0); }

    @Test void searchMergesAddressAndKeywordResultsAsLatitudeLongitude() throws Exception {
        mvc.perform(get("/api/geocoding/search").param("query", "꼭꼭로 12").header("X-App-Api-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.coordinateSystem").value("WGS84"))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].type").value("address"))
                .andExpect(jsonPath("$.results[0].name").value("서울 합성구 꼭꼭로 12"))
                .andExpect(jsonPath("$.results[0].latitude").value(37.5001))
                .andExpect(jsonPath("$.results[0].longitude").value(127.0001))
                .andExpect(jsonPath("$.results[1].type").value("place"))
                .andExpect(jsonPath("$.results[1].name").value("합성 카페"))
                .andExpect(jsonPath("$.results[1].address").value("서울 합성구 꼭꼭로 24"));
        assertEquals(2, REQUESTS.size());
        assertTrue(REQUESTS.stream().allMatch(r -> r.startsWith("KakaoAK " + SYNTHETIC_KEY)));
        assertTrue(REQUESTS.get(0).contains("query=꼭꼭로 12"));
    }

    @Test void reverseSendsLongitudeAsXAndLatitudeAsY() throws Exception {
        mvc.perform(get("/api/geocoding/reverse").param("latitude", "37.5001").param("longitude", "127.0001")
                        .header("X-App-Api-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("서울 합성구 꼭꼭동 1"))
                .andExpect(jsonPath("$.roadAddress").value("서울 합성구 꼭꼭로 12"));
        assertTrue(REQUESTS.get(0).contains("x=127.0001"));
        assertTrue(REQUESTS.get(0).contains("y=37.5001"));
    }

    @Test void emptyResultsAreNotErrors() throws Exception {
        mvc.perform(get("/api/geocoding/search").param("query", "없는곳").header("X-App-Api-Key", "test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test void rejectsInvalidInputWithoutCallingKakao() throws Exception {
        mvc.perform(get("/api/geocoding/search").param("query", "   ").header("X-App-Api-Key", "test-key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/geocoding/search").param("query", "가".repeat(101)).header("X-App-Api-Key", "test-key"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/geocoding/reverse").param("latitude", "91").param("longitude", "127")
                        .header("X-App-Api-Key", "test-key"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_COORDINATE"));
        mvc.perform(get("/api/geocoding/search").header("X-App-Api-Key", "test-key")).andExpect(status().isBadRequest());
        assertTrue(REQUESTS.isEmpty());
    }

    @Test void upstreamFailuresAreMappedWithoutLeakingDetails() throws Exception {
        forcedStatus = 401;
        String body = mvc.perform(get("/api/geocoding/search").param("query", "꼭꼭로").header("X-App-Api-Key", "test-key"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error.code").value("GEOCODING_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(SYNTHETIC_KEY));
        assertFalse(body.contains("upstream-secret-detail"));
        forcedStatus = 429;
        mvc.perform(get("/api/geocoding/search").param("query", "꼭꼭로").header("X-App-Api-Key", "test-key"))
                .andExpect(status().isTooManyRequests());
    }

    @Test void requiresAppKeyLikeOtherApis() throws Exception {
        mvc.perform(get("/api/geocoding/search").param("query", "꼭꼭로")).andExpect(status().isUnauthorized());
    }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/local/", exchange -> {
                String query = URLDecoder.decode(String.valueOf(exchange.getRequestURI().getRawQuery()), StandardCharsets.UTF_8);
                REQUESTS.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + query);
                String path = exchange.getRequestURI().getPath();
                String json;
                int code = forcedStatus;
                if (code != 200) json = "{\"message\":\"upstream-secret-detail\"}";
                else if (query.contains("없는곳")) json = "{\"documents\":[],\"meta\":{\"total_count\":0}}";
                else if (path.endsWith("address.json")) json = """
                        {"documents":[{"address_name":"서울 합성구 꼭꼭동 1","x":"127.0001","y":"37.5001",
                          "address":{"address_name":"서울 합성구 꼭꼭동 1"},"road_address":{"address_name":"서울 합성구 꼭꼭로 12"}},
                          {"address_name":"좌표 없는 결과","x":"","y":""}]}""";
                else if (path.endsWith("keyword.json")) json = """
                        {"documents":[{"place_name":"합성 카페","address_name":"서울 합성구 꼭꼭동 2",
                          "road_address_name":"서울 합성구 꼭꼭로 24","x":"127.0002","y":"37.5002"}]}""";
                else json = """
                        {"documents":[{"address":{"address_name":"서울 합성구 꼭꼭동 1"},"road_address":{"address_name":"서울 합성구 꼭꼭로 12"}}]}""";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(code, bytes.length);
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
