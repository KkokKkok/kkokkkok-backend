package com.kkokkkok.backend.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

@Component
public class ImageMetadataExtractor {
    public ExtractedMetadata extract(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            var coordinates = gps == null ? null : gps.getGeoLocation();
            ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            LocalDateTime capturedAt = null;
            if (exif != null) {
                String originalDate = exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                if (originalDate != null) {
                    try { capturedAt = LocalDateTime.parse(originalDate, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")); }
                    catch (DateTimeParseException ignored) { /* malformed optional date */ }
                }
            }
            boolean hasCoordinates = coordinates != null;
            return new ExtractedMetadata(
                    hasCoordinates ? coordinates.getLatitude() : null,
                    hasCoordinates ? coordinates.getLongitude() : null,
                    capturedAt,
                    hasCoordinates ? "available" : "unavailable");
        } catch (Exception ignored) {
            // Metadata is optional. A missing or malformed EXIF block must not block a valid image upload.
            return new ExtractedMetadata(null, null, null, "unavailable");
        }
    }

    public record ExtractedMetadata(Double latitude, Double longitude, LocalDateTime capturedAt, String locationStatus) {}
}
