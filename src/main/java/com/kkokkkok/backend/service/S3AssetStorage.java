package com.kkokkkok.backend.service;

import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

public final class S3AssetStorage implements AssetStorage {
    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3AssetStorage(S3Client client, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("S3 bucket is required");
        if (prefix == null || !prefix.matches("([a-zA-Z0-9_-]+/)*"))
            throw new IllegalArgumentException("Invalid S3 object prefix");
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    @Override
    public String save(UUID id, byte[] bytes, String contentType) {
        String key = AssetStorage.key(id, contentType);
        try {
            client.putObject(request -> request.bucket(bucket).key(prefix + key).contentType(contentType)
                    .ifNoneMatch("*"), RequestBody.fromBytes(bytes));
            return key;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Asset storage operation failed", failure);
        }
    }

    @Override
    public byte[] read(String key) {
        String objectKey = prefix + AssetStorage.validateKey(key);
        try {
            return client.getObjectAsBytes(request -> request.bucket(bucket).key(objectKey)).asByteArray();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Asset storage operation failed", failure);
        }
    }

    @Override
    public void delete(String key) {
        String objectKey = prefix + AssetStorage.validateKey(key);
        try {
            client.deleteObject(request -> request.bucket(bucket).key(objectKey));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Asset storage operation failed", failure);
        }
    }
}
