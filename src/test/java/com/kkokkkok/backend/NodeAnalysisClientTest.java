package com.kkokkkok.backend;

import com.sun.net.httpserver.HttpServer;
import com.kkokkkok.backend.client.NodeAnalysisClient;
import com.kkokkkok.backend.config.AnalysisClientConfig;
import com.kkokkkok.backend.converter.InspectionConverter;
import com.kkokkkok.backend.validation.InspectionValidator;
import com.kkokkkok.backend.web.dto.InspectionRequest;
import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeAnalysisClientTest {
    @Test void sendsMultipartAndRetrievesJobWithoutLeakingUpstreamError() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var id = UUID.randomUUID(); var key = UUID.randomUUID();
        var request = new AtomicReference<String>();
        server.createContext("/api/inspections", exchange -> {
            if (!"secret".equals(exchange.getRequestHeaders().getFirst("X-Analysis-Api-Key"))) { exchange.sendResponseHeaders(401,-1); exchange.close(); return; }
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Idempotency-Key") + " " + new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var body = ("{\"inspectionId\":\""+id+"\",\"status\":\"queued\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new NodeAnalysisClient(new AnalysisClientConfig().analysisRestClient("http://127.0.0.1:"+server.getAddress().getPort(),"secret",2),"configured");
            var assetId=UUID.randomUUID();
            var input = new com.kkokkkok.backend.domain.InspectionSubmission(assetId,
                    new byte[]{(byte)255,(byte)216,(byte)255,0},"image/jpeg",null,null,null,"synthetic-fingerprint");
            assertEquals(id.toString(),client.create(input,key).get("inspectionId"));
            assertTrue(request.get().contains(key.toString()));
            assertTrue(request.get().contains("compareHistory")); assertFalse(request.get().contains("private.jpg"));
            assertEquals("queued",client.get(id).get("status"));
            assertEquals(502,assertThrows(ApiFailure.class,()->new InspectionConverter().fromNode(client.get(id),UUID.randomUUID())).status);
            var unauthenticated = new NodeAnalysisClient(new AnalysisClientConfig().analysisRestClient("http://127.0.0.1:"+server.getAddress().getPort(),"wrong",2),"configured");
            assertEquals(502,assertThrows(ApiFailure.class,()->unauthenticated.get(id)).status);
        } finally {server.stop(0);}
    }

    @Test void rejectsInvalidPhotoAndReportsMissingConfiguration() {
        var client = new NodeAnalysisClient(new AnalysisClientConfig().analysisRestClient("","",2),"");
        assertEquals(503,assertThrows(ApiFailure.class,()->client.get(UUID.randomUUID())).status);
        UUID assetId=UUID.randomUUID();
        var asset=new com.kkokkkok.backend.service.ImageAssetService.StoredAsset(assetId,"image/jpeg","not an image".getBytes(),null,null,null);
        assertEquals(415,assertThrows(ApiFailure.class,()->new InspectionValidator().validate(new InspectionRequest(assetId),asset)).status);
    }
}
