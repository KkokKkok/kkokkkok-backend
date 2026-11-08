package com.kkokkkok.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CumulativeApiTest {
    private static final Path STORAGE = Path.of("build/cumulative-test-assets").toAbsolutePath();
    private static final byte[] SYNTHETIC_JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
    private static final UUID INSTALLATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> "test-key");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:cumulative-test;DB_CLOSE_DELAY=-1");
        registry.add("assets.storage-root", () -> STORAGE.toString());
    }

    @BeforeEach
    void cleanStorage() throws Exception {
        if (Files.exists(STORAGE)) {
            try (var paths = Files.walk(STORAGE)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    @Test
    void keepsPlacePrivateAndPersistsNotAssessedRunWhenThereIsNoInspection() throws Exception {
        MvcResult placeResponse = mvc.perform(post("/api/places")
                        .header("X-App-Api-Key", "test-key").header("X-Installation-Id", INSTALLATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"우리 집\",\"category\":\"home\",\"latitude\":37.5,\"longitude\":127.0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andReturn();
        String placeId = com.jayway.jsonpath.JsonPath.read(placeResponse.getResponse().getContentAsString(), "$.placeId");

        mvc.perform(get("/api/places/{id}", placeId).header("X-App-Api-Key", "test-key")
                        .header("X-Installation-Id", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        var photo = new MockMultipartFile("photo", "synthetic.jpg", "image/jpeg", SYNTHETIC_JPEG);
        MvcResult uploaded = mvc.perform(multipart("/api/assets").file(photo).header("X-App-Api-Key", "test-key"))
                .andExpect(status().isCreated()).andReturn();
        String assetId = com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(), "$.assetId");

        mvc.perform(put("/api/assets/{id}/capture-location", assetId)
                        .header("X-App-Api-Key", "test-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.5002,\"longitude\":127.0002,\"source\":\"manual\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locationStatus").value("manual"))
                .andExpect(jsonPath("$.latitude").doesNotExist());

        mvc.perform(post("/api/places/{id}/assets", placeId)
                        .header("X-App-Api-Key", "test-key").header("X-Installation-Id", INSTALLATION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":\"" + assetId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assetId").value(assetId));

        MvcResult run = mvc.perform(post("/api/cumulative-runs")
                        .header("X-App-Api-Key", "test-key").header("X-Installation-Id", INSTALLATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":\"" + placeId + "\",\"assetIds\":[\"" + assetId + "\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.score").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.scoreStatus").value("not_assessed"))
                .andExpect(jsonPath("$.excluded[0].reason").value("완료된 사진 검사가 없어 제외했어요."))
                .andReturn();
        String runId = com.jayway.jsonpath.JsonPath.read(run.getResponse().getContentAsString(), "$.runId");
        String response = mvc.perform(get("/api/cumulative-runs/{id}", runId)
                        .header("X-App-Api-Key", "test-key").header("X-Installation-Id", INSTALLATION_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(runId))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("37.5002"));
        assertFalse(response.contains("127.0002"));
        assertTrue(response.contains("cumulative-score-v1-provisional"));
    }
}
