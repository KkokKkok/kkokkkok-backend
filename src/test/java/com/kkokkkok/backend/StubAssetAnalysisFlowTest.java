package com.kkokkkok.backend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class StubAssetAnalysisFlowTest {
    private static final Path STORAGE = Path.of("build/test-stub-assets").toAbsolutePath();
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> "stub-test-key");
        registry.add("analysis.provider", () -> "stub");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:stub-asset-flow;DB_CLOSE_DELAY=-1");
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
    void storedAssetCanRunThroughClearlyMarkedStubAnalysis() throws Exception {
        var photo = new MockMultipartFile("photo", "synthetic.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0});
        MvcResult upload = mvc.perform(multipart("/api/assets").file(photo)
                        .header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isCreated()).andReturn();
        String assetId = com.jayway.jsonpath.JsonPath.read(upload.getResponse().getContentAsString(), "$.assetId");
        UUID requestId = UUID.randomUUID();

        MvcResult started = mvc.perform(post("/api/inspections")
                        .header("X-App-Api-Key", "stub-test-key")
                        .header("Idempotency-Key", requestId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + assetId + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.pipelineVersion").value("stub-v1"))
                .andReturn();
        String inspectionId = com.jayway.jsonpath.JsonPath.read(started.getResponse().getContentAsString(), "$.inspectionId");

        mvc.perform(get("/api/inspections/{id}", inspectionId).header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.mockNotice").value("모의 예시 결과이며 실제 사진을 분석하지 않았습니다."))
                .andExpect(jsonPath("$.riskFactors[0].bbox[0]").value(0.12));
    }

    @Test
    void malformedAssetIdIsReportedAsBadRequest() throws Exception {
        mvc.perform(post("/api/inspections")
                        .header("X-App-Api-Key", "stub-test-key")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assetHistoryContainsOnlyThatPhotosRunsInNewestFirstOrder() throws Exception {
        String assetId = uploadAsset();
        String otherAssetId = uploadAsset();
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();
        UUID otherKey = UUID.randomUUID();
        startAnalysis(assetId, firstKey);
        Thread.sleep(5);
        startAnalysis(assetId, secondKey);
        startAnalysis(otherAssetId, otherKey);

        mvc.perform(get("/api/inspections").param("assetId", assetId)
                        .header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].inspectionId").value(secondKey.toString()))
                .andExpect(jsonPath("$[1].inspectionId").value(firstKey.toString()))
                .andExpect(jsonPath("$[0].assetId").value(assetId));
    }

    @Test
    void stubEditKeepsOriginalAndCreatesExplicitlyMockedDerivedAsset() throws Exception {
        String sourceAssetId = uploadAsset();
        UUID inspectionId = UUID.randomUUID();
        startAnalysis(sourceAssetId, inspectionId);
        mvc.perform(get("/api/inspections/{id}", inspectionId).header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"));

        mvc.perform(post("/api/edits").header("X-App-Api-Key", "stub-test-key")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inspectionId\":\"" + inspectionId + "\",\"cueIds\":[\"unknown-cue\"]}"))
                .andExpect(status().isBadRequest());

        UUID editId = UUID.randomUUID();
        String editRequest = "{\"inspectionId\":\"" + inspectionId + "\",\"cueIds\":[\"stub-clue-1\"],\"preservePrompt\":\"분위기는 유지해 주세요.\"}";
        MvcResult created = mvc.perform(post("/api/edits")
                        .header("X-App-Api-Key", "stub-test-key")
                        .header("Idempotency-Key", editId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(editRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.mockNotice").value("모의 편집입니다. 이미지 내용은 수정하지 않았습니다."))
                .andExpect(jsonPath("$.verificationStatus").value("queued"))
                .andReturn();
        String editedAssetId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.editedAssetId");
        String verificationInspectionId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.verificationInspectionId");

        mvc.perform(get("/api/inspections/{id}", verificationInspectionId).header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(editedAssetId))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.mock").value(true));

        mvc.perform(get("/api/assets/{id}", sourceAssetId).header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assetRole").value("original"));
        mvc.perform(get("/api/assets/{id}", editedAssetId).header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetRole").value("edited"))
                .andExpect(jsonPath("$.parentAssetId").value(sourceAssetId))
                .andExpect(jsonPath("$.mock").value(true));
        MvcResult originalBytes = mvc.perform(get("/api/assets/{id}/content", sourceAssetId)
                        .header("X-App-Api-Key", "stub-test-key")).andExpect(status().isOk()).andReturn();
        MvcResult editedBytes = mvc.perform(get("/api/assets/{id}/content", editedAssetId)
                        .header("X-App-Api-Key", "stub-test-key")).andExpect(status().isOk()).andReturn();
        assertArrayEquals(originalBytes.getResponse().getContentAsByteArray(), editedBytes.getResponse().getContentAsByteArray());

        mvc.perform(get("/api/edits/{id}", editId).header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.editedAssetId").value(editedAssetId));
        mvc.perform(post("/api/edits").header("X-App-Api-Key", "stub-test-key")
                        .header("Idempotency-Key", editId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(editRequest))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.editedAssetId").value(editedAssetId));
        mvc.perform(post("/api/edits").header("X-App-Api-Key", "stub-test-key")
                        .header("Idempotency-Key", editId.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(editRequest.replace("분위기는 유지해 주세요.", "음식은 유지해 주세요.")))
                .andExpect(status().isConflict());
    }

    private String uploadAsset() throws Exception {
        var photo = new MockMultipartFile("photo", "synthetic.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0});
        MvcResult upload = mvc.perform(multipart("/api/assets").file(photo)
                        .header("X-App-Api-Key", "stub-test-key"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(upload.getResponse().getContentAsString(), "$.assetId");
    }

    private void startAnalysis(String assetId, UUID requestId) throws Exception {
        mvc.perform(post("/api/inspections")
                        .header("X-App-Api-Key", "stub-test-key")
                        .header("Idempotency-Key", requestId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + assetId + "\"}"))
                .andExpect(status().isAccepted());
    }
}
