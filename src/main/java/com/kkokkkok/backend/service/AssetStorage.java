package com.kkokkkok.backend.service;

import java.util.UUID;

/** Private object storage. Keys remain opaque server metadata, never public URLs. */
public interface AssetStorage {
    String save(UUID id, byte[] bytes, String contentType);
    byte[] read(String key);
    void delete(String key);

    static String key(UUID id, String contentType) {
        return id + switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new IllegalArgumentException("Unsupported content type");
        };
    }

    static String validateKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|png|webp)"))
            throw new IllegalArgumentException("Invalid storage key");
        return key;
    }
}
