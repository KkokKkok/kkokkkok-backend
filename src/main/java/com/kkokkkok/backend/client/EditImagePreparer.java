package com.kkokkkok.backend.client;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.EditSubmission;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/** Normalizes the EXIF orientation and turns selected normalized bbox values into one alpha mask. */
@Component
class EditImagePreparer {
    private static final long MAX_PIXELS = 24_000_000L;
    private static final int MAX_PROVIDER_IMAGE_BYTES = 50 * 1024 * 1024;
    private static final int MAX_STORED_IMAGE_BYTES = 10 * 1024 * 1024;

    PreparedEditImage prepare(EditSubmission submission) {
        if (!"image/jpeg".equals(submission.contentType()) && !"image/png".equals(submission.contentType()))
            throw new ApiFailure(415, "EDIT_IMAGE_FORMAT_UNSUPPORTED", "실제 보호 편집은 JPEG와 PNG 사진을 지원합니다.");

        BufferedImage source = decode(submission.image());
        if ((long) source.getWidth() * source.getHeight() > MAX_PIXELS)
            throw new ApiFailure(413, "EDIT_IMAGE_TOO_LARGE", "보호 편집을 위한 사진 해상도가 너무 큽니다.");

        BufferedImage oriented = applyOrientation(source, readOrientation(submission.image()));
        byte[] imagePng = encodePng(oriented);
        if (imagePng.length > MAX_PROVIDER_IMAGE_BYTES)
            throw new ApiFailure(413, "EDIT_IMAGE_TOO_LARGE", "편집용 PNG 사진은 50MB 이하여야 합니다.");
        var visualCues = new ArrayList<Map<String, Object>>();
        BufferedImage mask = new BufferedImage(oriented.getWidth(), oriented.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mask.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.fillRect(0, 0, mask.getWidth(), mask.getHeight());
            graphics.setComposite(AlphaComposite.Clear);
            for (Map<String, Object> cue : submission.selectedCues()) {
                if (isMetadataOnly(cue)) continue;
                double[] bbox = readBbox(cue);
                int left = clamp((int) Math.floor(bbox[0] * mask.getWidth()), 0, mask.getWidth() - 1);
                int top = clamp((int) Math.floor(bbox[1] * mask.getHeight()), 0, mask.getHeight() - 1);
                int right = clamp((int) Math.ceil(bbox[2] * mask.getWidth()), left + 1, mask.getWidth());
                int bottom = clamp((int) Math.ceil(bbox[3] * mask.getHeight()), top + 1, mask.getHeight());
                graphics.fillRect(left, top, right - left, bottom - top);
                visualCues.add(cue);
            }
        } finally {
            graphics.dispose();
        }

        byte[] maskPng = visualCues.isEmpty() ? null : encodePng(mask);
        return new PreparedEditImage(imagePng, maskPng, oriented.getWidth(), oriented.getHeight(), List.copyOf(visualCues));
    }

    byte[] sanitizeGeneratedImage(byte[] bytes) {
        BufferedImage decoded = decode(bytes);
        if ((long) decoded.getWidth() * decoded.getHeight() > MAX_PIXELS)
            throw new ApiFailure(502, "INVALID_EDIT_OUTPUT", "편집 결과의 해상도가 허용 범위를 벗어났습니다.");
        byte[] png = encodePng(decoded);
        if (png.length > MAX_STORED_IMAGE_BYTES)
            throw new ApiFailure(413, "EDIT_OUTPUT_TOO_LARGE", "수정 결과 이미지는 10MB 이하여야 합니다.");
        return png;
    }

    private BufferedImage decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IOException("unsupported image");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("unsupported image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS)
                    throw new ApiFailure(413, "EDIT_IMAGE_TOO_LARGE", "보호 편집을 위한 사진 해상도가 너무 큽니다.");
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("unsupported image");
                return image;
            } finally {
                reader.dispose();
            }
        } catch (ApiFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new ApiFailure(415, "EDIT_IMAGE_DECODE_FAILED", "사진을 편집용 이미지로 읽을 수 없습니다.");
        }
    }

    private byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output))
                throw new IOException("PNG writer unavailable");
            return output.toByteArray();
        } catch (IOException failure) {
            throw new ApiFailure(502, "EDIT_IMAGE_PREPARATION_FAILED", "편집용 사진을 준비하지 못했습니다.");
        }
    }

    private int readOrientation(byte[] bytes) {
        try {
            var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            return directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)
                    ? directory.getInt(ExifIFD0Directory.TAG_ORIENTATION) : 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private BufferedImage applyOrientation(BufferedImage source, int orientation) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swapsDimensions = orientation >= 5 && orientation <= 8;
        BufferedImage target = new BufferedImage(swapsDimensions ? height : width,
                swapsDimensions ? width : height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private double[] readBbox(Map<String, Object> cue) {
        Object space = cue.get("coordinateSpace");
        if (space != null && !("analysis-normalized".equals(space)
                || "exif-oriented-normalized".equals(space)))
            throw invalidGeometry();
        Object raw = cue.get("bbox");
        if (!(raw instanceof List<?> values) || values.size() != 4) throw invalidGeometry();
        double[] bbox = new double[4];
        for (int index = 0; index < 4; index++) {
            if (!(values.get(index) instanceof Number number)) throw invalidGeometry();
            bbox[index] = number.doubleValue();
            if (!Double.isFinite(bbox[index]) || bbox[index] < 0 || bbox[index] > 1) throw invalidGeometry();
        }
        if (bbox[0] >= bbox[2] || bbox[1] >= bbox[3]) throw invalidGeometry();
        return bbox;
    }

    private boolean isMetadataOnly(Map<String, Object> cue) {
        String type = String.valueOf(cue.getOrDefault("type", "")).toLowerCase();
        if (cue.get("bbox") != null) return false;
        if (type.contains("gps") || type.contains("exif") || type.contains("metadata")) return true;
        throw invalidGeometry();
    }

    private ApiFailure invalidGeometry() {
        return new ApiFailure(409, "ANALYSIS_CUE_GEOMETRY_UNAVAILABLE",
                "선택한 단서의 bbox가 없거나 사진 기준과 일치하지 않습니다.");
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
