package com.kkokkkok.backend;

import com.sun.net.httpserver.HttpServer;
import com.kkokkkok.backend.repository.InspectionRepository;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class InspectionFlowTest {
    static final UUID JOB=UUID.randomUUID();
    static final AtomicInteger POSTS=new AtomicInteger();
    static final AtomicInteger NODE_STATUS=new AtomicInteger(200);
    static final HttpServer NODE=startNode();
    @Value("${local.server.port}") int port;
    @Autowired InspectionRepository repository;
    final HttpClient http=HttpClient.newHttpClient();

    static HttpServer startNode() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/inspections",e->{
                e.getRequestBody().readAllBytes();
                boolean post=e.getRequestMethod().equals("POST"); if(post) POSTS.incrementAndGet();
                String json=NODE_STATUS.get()!=200?"private upstream debug":
                    "{\"inspectionId\":\""+JOB+"\",\"status\":\""+(post?"queued":"completed")+"\",\"riskFactors\":[],\"riskSummary\":{\"score\":null}}";
                byte[] body=json.getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type","application/json");
                e.sendResponseHeaders(NODE_STATUS.get(),body.length);e.getResponseBody().write(body);e.close();
            }); server.start(); return server;
        } catch(Exception e) {throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("analysis.base-url",()->"http://127.0.0.1:"+NODE.getAddress().getPort());
        r.add("app.api-key",()->"test-key");
        r.add("spring.datasource.url",()->"jdbc:h2:mem:flow-test;DB_CLOSE_DELAY=-1");
    }
    @AfterAll static void stopNode() {NODE.stop(0);}
    HttpResponse<String> post(UUID key,String address) throws Exception {
        String prefix="--boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n";
        String suffix="\r\n--boundary\r\nContent-Disposition: form-data; name=\"address\"\r\n\r\n"+address+"\r\n--boundary--\r\n";
        var body=new java.io.ByteArrayOutputStream();body.write(prefix.getBytes(StandardCharsets.UTF_8));
        body.write(new byte[]{(byte)255,(byte)216,(byte)255,0});body.write(suffix.getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/inspections"))
            .header("X-App-Api-Key","test-key").header("Idempotency-Key",key.toString())
            .header("Content-Type","multipart/form-data; boundary=boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void fullHttpFlowPersistsJobAndRejectsChangedRetry() throws Exception {
        UUID key=UUID.randomUUID();
        var created=post(key,"synthetic address");
        assertEquals(202,created.statusCode(),created.body());
        assertEquals("/api/inspections/"+JOB,created.headers().firstValue("Location").orElseThrow());
        assertTrue(created.body().contains("\"riskFactors\":[]"));
        assertFalse(created.body().contains("\"details\""));
        assertEquals(JOB,repository.findById(key).orElseThrow().getInspectionId());
        assertEquals(202,post(key,"synthetic address").statusCode());
        int calls=POSTS.get();
        assertEquals(409,post(key,"different address").statusCode());
        assertEquals(calls,POSTS.get());
        var uri=URI.create("http://127.0.0.1:"+port+"/api/inspections/"+JOB);
        var get=HttpRequest.newBuilder(uri).header("X-App-Api-Key","test-key").GET().build();
        assertEquals(200,http.send(get,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("completed",repository.findById(key).orElseThrow().getStatus());
        assertEquals(401,http.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var invalidPost=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/inspections"))
            .header("X-App-Api-Key","test-key").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(415,http.send(invalidPost,HttpResponse.BodyHandlers.ofString()).statusCode());
        NODE_STATUS.set(500);
        var failed=http.send(get,HttpResponse.BodyHandlers.ofString());
        assertEquals(502,failed.statusCode());assertFalse(failed.body().contains("private upstream"));
        assertEquals("completed",repository.findById(key).orElseThrow().getStatus());
    }
}
