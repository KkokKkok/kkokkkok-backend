package com.kkokkkok.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Development storage implementation. Replace behind this boundary when deployment storage is chosen. */
@Component
public class LocalAssetStorage {
    private final Path root;

    public LocalAssetStorage(@Value("${assets.storage-root:./private-data/assets}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public String save(UUID id, byte[] bytes, String contentType) {
        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new IllegalArgumentException("Unsupported content type");
        };
        String key = id + extension;
        try {
            Files.createDirectories(root);
            Files.write(resolve(key), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("Asset storage operation failed", e);
        }
    }

    public byte[] read(String key) {
        try { return Files.readAllBytes(resolve(key)); }
        catch (IOException e) { throw new IllegalStateException("Asset storage operation failed", e); }
    }

    public void delete(String key) {
        try { Files.deleteIfExists(resolve(key)); }
        catch (IOException e) { throw new IllegalStateException("Asset storage operation failed", e); }
    }

    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.getParent().equals(root)) throw new IllegalArgumentException("Invalid storage key");
        return resolved;
    }
}
