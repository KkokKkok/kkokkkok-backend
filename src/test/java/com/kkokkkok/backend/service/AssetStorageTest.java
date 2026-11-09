package com.kkokkkok.backend.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import static org.junit.jupiter.api.Assertions.*;

class AssetStorageTest {
    @TempDir Path directory;
    private static final byte[] PIXELS = {1, 2, 3, 4}; // Synthetic bytes only; image decoding belongs to upload validation.

    @Test void localStoragePreservesBytesAndRejectsTraversalAndOverwrite() throws Exception {
        var storage = new LocalAssetStorage(directory.toString());
        UUID id = UUID.randomUUID();
        String key = storage.save(id, PIXELS, "image/png");
        assertArrayEquals(PIXELS, storage.read(key));
        assertThrows(IllegalStateException.class, () -> storage.save(id, new byte[]{9}, "image/png"));
        assertArrayEquals(PIXELS, storage.read(key));
        assertThrows(IllegalArgumentException.class, () -> storage.read("../outside.png"));
        storage.delete(key);
        storage.delete(key);
        assertFalse(Files.exists(directory.resolve(key)));
    }

    @Test void s3SdkSendsPrivateSignedRequestsAndPropagatesFailures() throws Exception {
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger signed = new AtomicInteger();
        AtomicInteger forcedStatus = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            var headers = exchange.getRequestHeaders();
            if (headers.getFirst("Authorization") != null && headers.getFirst("Authorization").startsWith("AWS4-HMAC-SHA256 ")) signed.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            int status = forcedStatus.get();
            byte[] response = new byte[0];
            if (status == 0) {
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        if (!"image/png".equals(headers.getFirst("Content-Type")) || headers.containsKey("x-amz-acl")
                                || !"*".equals(headers.getFirst("If-None-Match"))) status = 400;
                        else if (objects.putIfAbsent(path, exchange.getRequestBody().readAllBytes()) != null) status = 412;
                        else status = 200;
                    }
                    case "GET" -> {
                        response = objects.get(path);
                        status = response == null ? 404 : 200;
                        if (response == null) response = new byte[0];
                    }
                    case "DELETE" -> { objects.remove(path); status = 204; }
                    default -> status = 405;
                }
            }
            if (status >= 400) {
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                response = "<Error><Code>AccessDenied</Code><Message>synthetic error</Message></Error>".getBytes();
            }
            exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
            if (response.length > 0) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (S3Client client = S3Client.builder().region(Region.AP_NORTHEAST_2)
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .forcePathStyle(true).serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic", "synthetic"))).build()) {
            var storage = new S3AssetStorage(client, "synthetic-bucket", "assets/");
            UUID id = UUID.randomUUID();
            String key = storage.save(id, PIXELS, "image/png");
            assertTrue(objects.containsKey("/synthetic-bucket/assets/" + key));
            assertArrayEquals(PIXELS, storage.read(key));
            assertThrows(IllegalStateException.class, () -> storage.save(id, new byte[]{9}, "image/png"));
            assertArrayEquals(PIXELS, storage.read(key));
            int before = calls.get();
            assertThrows(IllegalArgumentException.class, () -> storage.delete("../outside.png"));
            assertEquals(before, calls.get());
            forcedStatus.set(403);
            assertThrows(IllegalStateException.class, () -> storage.read(key));
            assertThrows(IllegalStateException.class, () -> storage.delete(key));
            assertThrows(IllegalStateException.class, () -> storage.save(UUID.randomUUID(), PIXELS, "image/png"));
            forcedStatus.set(0);
            storage.delete(key);
            storage.delete(key);
            assertTrue(objects.isEmpty());
            assertThrows(IllegalStateException.class, () -> storage.read(key));
            assertEquals(calls.get(), signed.get());
        } finally { server.stop(0); }
    }
}
