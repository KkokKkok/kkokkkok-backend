package com.kkokkkok.backend.client;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.EditSubmission;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * Normalizes the EXIF orientation and turns the selected cues' areas into one alpha mask: a text cue's
 * OCR polygons, an object cue's segmentation mask, and the bbox for a cue that has neither.
 */
@Component
class EditImagePreparer {
    private static final long MAX_PIXELS = 50_000_000L;
    static final int MODEL_LONG_SIDE = 2048;
    static final int MODEL_MIN_LONG_SIDE = 1024;
    private static final int MAX_PROVIDER_IMAGE_BYTES = 50 * 1024 * 1024;
    private static final int MAX_STORED_IMAGE_BYTES = 10 * 1024 * 1024;

    PreparedEditImage prepare(EditSubmission submission) { return prepare(submission, Framing.FULL); }

    /**
     * The upright photo, the image and mask the editor sees, and what is needed to blend its output back.
     * {@link Framing#FULL} sends the whole photo; {@link Framing#CROP} only the clue areas plus surrounding
     * context, so the editor's output resolution is spent on the part it changes. Either way the editor's
     * image is at most {@value #MODEL_LONG_SIDE}px on the long side; blending happens at the original size.
     */
    PreparedEditImage prepare(EditSubmission submission, Framing framing) {
        if (!"image/jpeg".equals(submission.contentType()) && !"image/png".equals(submission.contentType()))
            throw new ApiFailure(415, "EDIT_IMAGE_FORMAT_UNSUPPORTED", "실제 보호 편집은 JPEG와 PNG 사진을 지원합니다.");

        BufferedImage source = decode(submission.image());
        if ((long) source.getWidth() * source.getHeight() > MAX_PIXELS)
            throw new ApiFailure(413, "EDIT_IMAGE_TOO_LARGE", "보호 편집을 위한 사진 해상도가 너무 큽니다.");

        BufferedImage oriented = applyOrientation(source, readOrientation(submission.image()));
        int width = oriented.getWidth(), height = oriented.getHeight();
        var visualCues = new ArrayList<Map<String, Object>>();
        // Clue shapes drawn white on black at the original size.
        BufferedImage shapes = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = shapes.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            for (Map<String, Object> cue : submission.selectedCues()) {
                if (isMetadataOnly(cue)) continue;
                double[] bbox = readBbox(cue);
                // The exact area when the analysis gave one (text polygon, object mask); else the box.
                var cueShapes = EditCueShapes.of(cue, bbox);
                if (!cueShapes.isEmpty()) {
                    for (var shape : cueShapes) shape.fill(graphics, width, height);
                    visualCues.add(cue);
                    continue;
                }
                int left = clamp((int) Math.floor(bbox[0] * width), 0, width - 1);
                int top = clamp((int) Math.floor(bbox[1] * height), 0, height - 1);
                int right = clamp((int) Math.ceil(bbox[2] * width), left + 1, width);
                int bottom = clamp((int) Math.ceil(bbox[3] * height), top + 1, height);
                graphics.fillRect(left, top, right - left, bottom - top);
                visualCues.add(cue);
            }
        } finally {
            graphics.dispose();
        }

        if (visualCues.isEmpty()) {
            // Metadata-only: the photo is re-encoded at its own size to drop EXIF; nothing goes to the editor.
            byte[] imagePng = encodePng(oriented);
            if (imagePng.length > MAX_PROVIDER_IMAGE_BYTES)
                throw new ApiFailure(413, "EDIT_IMAGE_TOO_LARGE", "편집용 PNG 사진은 50MB 이하여야 합니다.");
            return new PreparedEditImage(imagePng, null, width, height, List.of(), oriented, null, 0, 0,
                    new Rectangle(0, 0, width, height));
        }

        // Grow the exact clue shapes so glyph edges and outlines are repainted too, and remember each pixel's
        // distance to them so the edit can be blended back only there.
        byte[] raster = ((DataBufferByte) shapes.getRaster().getDataBuffer()).getData();
        boolean[] region = new boolean[raster.length];
        for (int i = 0; i < raster.length; i++) region[i] = raster[i] != 0;
        shapes = null;
        int grow = EditComposite.grow(width, height), feather = EditComposite.feather(width, height);
        short[] distance = EditComposite.distances(region, width, height, grow + feather + 64);
        region = null;
        Rectangle frame = framing == Framing.CROP
                ? EditComposite.cropAround(distance, width, height, grow, feather)
                : new Rectangle(0, 0, width, height);
        byte[][] model = modelView(oriented, distance, grow, feather, frame, framing == Framing.CROP);
        return new PreparedEditImage(model[0], model[1], width, height, List.copyOf(visualCues), oriented, distance,
                grow, feather, frame);
    }

    /**
     * The editor's view of one frame, at most MODEL_LONG_SIDE on the long side (crops are enlarged to at least
     * MODEL_MIN_LONG_SIDE so the editor has enough pixels to work with), and the grown mask sampled to match.
     */
    private byte[][] modelView(BufferedImage oriented, short[] distance, int grow, int feather, Rectangle frame, boolean crop) {
        int width = oriented.getWidth(), height = oriented.getHeight();
        int frameLong = Math.max(frame.width, frame.height);
        int targetLong = Math.min(MODEL_LONG_SIDE, crop ? Math.max(MODEL_MIN_LONG_SIDE, frameLong) : frameLong);
        double scale = targetLong / (double) frameLong;
        int modelWidth = Math.max(1, (int) Math.round(frame.width * scale));
        int modelHeight = Math.max(1, (int) Math.round(frame.height * scale));
        BufferedImage modelImage = EditComposite.resize(oriented.getSubimage(frame.x, frame.y, frame.width, frame.height),
                modelWidth, modelHeight);
        BufferedImage modelMask = new BufferedImage(modelWidth, modelHeight, BufferedImage.TYPE_INT_ARGB);
        int[] maskPixels = new int[modelWidth * modelHeight];
        for (int y = 0; y < modelHeight; y++) {
            int sy = Math.min(height - 1, frame.y + (int) ((y + 0.5) * frame.height / modelHeight));
            for (int x = 0; x < modelWidth; x++) {
                int sx = Math.min(width - 1, frame.x + (int) ((x + 0.5) * frame.width / modelWidth));
                maskPixels[y * modelWidth + x] = EditComposite.inModelMask(distance[sy * width + sx], grow, feather) ? 0 : 0xFF000000;
            }
        }
        modelMask.setRGB(0, 0, modelWidth, modelHeight, maskPixels, 0, modelWidth);
        return new byte[][]{encodePng(modelImage), encodePng(modelMask)};
    }

    /** FULL: the whole photo. CROP: the clue areas with surrounding context (sharper; the default). */
    enum Framing { FULL, CROP }

    /**
     * The original photo with the editor's output used only inside the (grown, faded) clue areas, at the
     * original size. Stored as PNG, or as a high-quality JPEG when a large photo's PNG would exceed the limit.
     */
    EditedImage compose(PreparedEditImage prepared, byte[] generated) {
        BufferedImage decoded = decode(generated);
        if ((long) decoded.getWidth() * decoded.getHeight() > MAX_PIXELS)
            throw new ApiFailure(502, "INVALID_EDIT_OUTPUT", "편집 결과의 해상도가 허용 범위를 벗어났습니다.");
        BufferedImage result = EditComposite.blend(prepared.source(), decoded, prepared.distance(), prepared.grow(),
                prepared.feather(), prepared.frame());
        byte[] png = encodePng(result);
        if (png.length <= MAX_STORED_IMAGE_BYTES) return new EditedImage(png, "image/png");
        if (!result.getColorModel().hasAlpha()) for (float quality : new float[]{0.95f, 0.92f, 0.88f}) {
            byte[] jpeg = encodeJpeg(result, quality);
            if (jpeg.length <= MAX_STORED_IMAGE_BYTES) return new EditedImage(jpeg, "image/jpeg");
        }
        throw new ApiFailure(413, "EDIT_OUTPUT_TOO_LARGE", "수정 결과 이미지는 10MB 이하여야 합니다.");
    }

    record EditedImage(byte[] bytes, String contentType) {}

    /** How much of the edited area the editor painted as an unexplained dark patch (0 = none). */
    double darkPatchShare(PreparedEditImage prepared, byte[] generated) {
        BufferedImage decoded = decode(generated);
        if ((long) decoded.getWidth() * decoded.getHeight() > MAX_PIXELS)
            throw new ApiFailure(502, "INVALID_EDIT_OUTPUT", "편집 결과의 해상도가 허용 범위를 벗어났습니다.");
        return EditComposite.darkPatchShare(prepared.source(), decoded, prepared.distance(), prepared.grow(),
                prepared.feather(), prepared.frame());
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

    private byte[] encodeJpeg(BufferedImage image, float quality) {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new ApiFailure(502, "EDIT_IMAGE_PREPARATION_FAILED", "편집 결과를 저장하지 못했습니다.");
        ImageWriter writer = writers.next();
        try (var output = new ByteArrayOutputStream(); var stream = ImageIO.createImageOutputStream(output)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
            stream.flush();
            return output.toByteArray();
        } catch (IOException failure) {
            throw new ApiFailure(502, "EDIT_IMAGE_PREPARATION_FAILED", "편집 결과를 저장하지 못했습니다.");
        } finally {
            writer.dispose();
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
