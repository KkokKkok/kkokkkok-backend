package com.kkokkkok.backend.client;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Keeps an image edit inside the clue areas. The model repaints the whole photo (and may return it at another
 * size), so its output is only used inside the mask: the selected clue shapes, grown by a margin so glyph edges
 * and outlines go too, then faded out over a few pixels. Everything else is the original pixel, unchanged.
 */
final class EditComposite {
    private static final int UNIT = 3;          // chamfer 3-4: one pixel step = 3, one diagonal step = 4
    private static final int MAX_COLOR_SHIFT = 48;

    private EditComposite() {}

    /** Full-strength margin around the clue shapes, in pixels; 0 for tiny images. */
    static int grow(int width, int height) { return (int) Math.floor(Math.min(width, height) * 0.008); }

    /** Width of the fade from edited to original pixels beyond the margin. */
    static int feather(int width, int height) { return (int) Math.floor(Math.min(width, height) * 0.005); }

    private static int ring(int width, int height) { return Math.max(2, (int) Math.floor(Math.min(width, height) * 0.01)); }

    /** Approximate distance (in thirds of a pixel) from each pixel to the nearest clue pixel, capped at {@code cap} pixels. */
    static int[] distances(boolean[] region, int width, int height, int cap) {
        int limit = (cap + 1) * UNIT;
        int[] d = new int[width * height];
        for (int i = 0; i < d.length; i++) d[i] = region[i] ? 0 : limit;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = y * width + x, v = d[i];
            if (v == 0) continue;
            if (x > 0) v = Math.min(v, d[i - 1] + 3);
            if (y > 0) {
                v = Math.min(v, d[i - width] + 3);
                if (x > 0) v = Math.min(v, d[i - width - 1] + 4);
                if (x < width - 1) v = Math.min(v, d[i - width + 1] + 4);
            }
            d[i] = Math.min(v, limit);
        }
        for (int y = height - 1; y >= 0; y--) for (int x = width - 1; x >= 0; x--) {
            int i = y * width + x, v = d[i];
            if (v == 0) continue;
            if (x < width - 1) v = Math.min(v, d[i + 1] + 3);
            if (y < height - 1) {
                v = Math.min(v, d[i + width] + 3);
                if (x < width - 1) v = Math.min(v, d[i + width + 1] + 4);
                if (x > 0) v = Math.min(v, d[i + width - 1] + 4);
            }
            d[i] = Math.min(v, limit);
        }
        return d;
    }

    /** Pixels the model is asked to repaint: the clue shapes plus margin and fade. */
    static boolean inModelMask(int distance, int grow, int feather) { return distance <= (grow + feather) * UNIT; }

    /** How much of the edited pixel to use: 1 inside shape + margin, fading to 0 across the feather. */
    static double weight(int distance, int grow, int feather) {
        double pixels = distance / (double) UNIT;
        if (pixels <= grow) return 1;
        if (feather <= 0 || pixels >= grow + feather) return 0;
        return (grow + feather - pixels) / feather;
    }

    /** The original with the edit blended in only where {@link #weight} is above 0, at the original size. */
    static BufferedImage blend(BufferedImage source, BufferedImage generated, int[] distance, int grow, int feather) {
        int width = source.getWidth(), height = source.getHeight();
        BufferedImage edited = scale(generated, width, height);
        int[] original = source.getRGB(0, 0, width, height, null, 0, width);
        int[] repaint = edited.getRGB(0, 0, width, height, null, 0, width);

        // Models often shift the whole photo's tone a little; match the edit to the original just outside the mask.
        int inner = (grow + feather) * UNIT, outer = (grow + feather + ring(width, height)) * UNIT;
        long[] sum = new long[3];
        long count = 0;
        for (int i = 0; i < original.length; i++) {
            if (distance[i] <= inner || distance[i] > outer) continue;
            for (int c = 0; c < 3; c++) sum[c] += channel(original[i], c) - channel(repaint[i], c);
            count++;
        }
        int[] shift = new int[3];
        if (count > 0) for (int c = 0; c < 3; c++)
            shift[c] = (int) Math.max(-MAX_COLOR_SHIFT, Math.min(MAX_COLOR_SHIFT, Math.round(sum[c] / (double) count)));

        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage result = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] out = new int[original.length];
        for (int i = 0; i < original.length; i++) {
            double w = weight(distance[i], grow, feather);
            if (w <= 0) { out[i] = original[i]; continue; }
            int pixel = original[i] & 0xFF000000;
            for (int c = 0; c < 3; c++) {
                int edit = clamp(channel(repaint[i], c) + shift[c]);
                int value = (int) Math.round(w * edit + (1 - w) * channel(original[i], c));
                pixel |= clamp(value) << (16 - 8 * c);
            }
            out[i] = pixel;
        }
        result.setRGB(0, 0, width, height, out, 0, width);
        return result;
    }

    private static BufferedImage scale(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height) return image;
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static int channel(int rgb, int c) { return (rgb >> (16 - 8 * c)) & 0xFF; }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
