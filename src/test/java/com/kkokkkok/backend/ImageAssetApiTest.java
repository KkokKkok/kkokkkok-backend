package com.kkokkkok.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
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
class ImageAssetApiTest {
    private static final Path STORAGE = Path.of("build/test-assets").toAbsolutePath();
    private static final byte[] SYNTHETIC_JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> "test-key");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:assets-test;DB_CLOSE_DELAY=-1");
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
    void uploadGalleryContentLabelsAndDeleteWorkWithSyntheticImage() throws Exception {
        var photo = new MockMultipartFile("photo", "synthetic.jpg", "image/jpeg", SYNTHETIC_JPEG);
        MvcResult uploaded = mvc.perform(multipart("/api/assets")
                        .file(photo).param("labels", "집").param("labels", "테스트")
                        .header("X-App-Api-Key", "test-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.locationStatus").value("unavailable"))
                .andExpect(jsonPath("$.labels[0]").value("집"))
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andReturn();
        String assetId = com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(), "$.assetId");

        mvc.perform(get("/api/assets").header("X-App-Api-Key", "test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].assetId").value(assetId));
        mvc.perform(get("/api/assets/{id}/content", assetId).header("X-App-Api-Key", "test-key"))
                .andExpect(status().isOk());
        MvcResult content = mvc.perform(get("/api/assets/{id}/content", assetId)
                        .header("X-App-Api-Key", "test-key"))
                .andReturn();
        assertArrayEquals(SYNTHETIC_JPEG, content.getResponse().getContentAsByteArray());

        mvc.perform(put("/api/assets/{id}/labels", assetId).header("X-App-Api-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"labels\":[\"회사\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.labels[0]").value("회사"));
        mvc.perform(delete("/api/assets/{id}", assetId).header("X-App-Api-Key", "test-key"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/assets/{id}", assetId).header("X-App-Api-Key", "test-key"))
                .andExpect(status().isNotFound());
    }
}
