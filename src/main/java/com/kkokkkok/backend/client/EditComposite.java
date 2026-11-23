package com.kkokkkok.backend.client;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps an image edit inside the clue areas. The model repaints the whole image it is given (and may return it at
 * another size), so its output is only used inside the mask: the selected clue shapes, grown by a margin so glyph
 * edges and outlines go too, then faded out over a few pixels. Everything else is the original pixel, unchanged.
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
    static short[] distances(boolean[] region, int width, int height, int cap) {
        int limit = Math.min(Short.MAX_VALUE, (cap + 1) * UNIT);
        short[] d = new short[width * height];
        for (int i = 0; i < d.length; i++) d[i] = region[i] ? 0 : (short) limit;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = y * width + x, v = d[i];
            if (v == 0) continue;
            if (x > 0) v = Math.min(v, d[i - 1] + 3);
            if (y > 0) {
                v = Math.min(v, d[i - width] + 3);
                if (x > 0) v = Math.min(v, d[i - width - 1] + 4);
                if (x < width - 1) v = Math.min(v, d[i - width + 1] + 4);
            }
            d[i] = (short) Math.min(v, limit);
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
            d[i] = (short) Math.min(v, limit);
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

    /**
     * The part of the photo to send when cropping: the edit area plus context on every side (half the area's own
     * size, at least 12% of the photo's short side) so the model sees what to continue, and always including the
     * colour-matching ring.
     */
    static Rectangle cropAround(short[] distance, int width, int height, int grow, int feather) {
        int reach = (grow + feather) * UNIT;
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (distance[y * width + x] > reach) continue;
            if (x < left) left = x; if (x > right) right = x;
            if (y < top) top = y; if (y > bottom) bottom = y;
        }
        if (right < 0) return new Rectangle(0, 0, width, height);
        return pad(new Rectangle(left, top, right - left + 1, bottom - top + 1), width, height);
    }

    private static Rectangle pad(Rectangle box, int width, int height) {
        int floor = Math.max((int) (Math.min(width, height) * 0.12), ring(width, height) + 2);
        int padX = Math.max(box.width / 2, floor), padY = Math.max(box.height / 2, floor);
        int x0 = Math.max(0, box.x - padX), y0 = Math.max(0, box.y - padY);
        int x1 = Math.min(width, box.x + box.width + padX), y1 = Math.min(height, box.y + box.height + padY);
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }

    /** High-quality resize (halving steps for large reductions, then bicubic). */
    static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage current = image;
        int w = image.getWidth(), h = image.getHeight();
        while (w / 2 >= width && h / 2 >= height) {
            w /= 2; h /= 2;
            current = draw(current, w, h);
        }
        return current.getWidth() == width && current.getHeight() == height && current != image
                ? current : draw(current, width, height);
    }

    private static BufferedImage draw(BufferedImage image, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
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

    /**
     * The original with the edit blended in only where {@link #weight} is above 0, at the original size.
     * {@code generated} shows {@code frame} (original pixels) at whatever size the model returned.
     */
    static BufferedImage blend(BufferedImage source, BufferedImage generated, short[] distance, int grow, int feather,
                               Rectangle frame) {
        int width = source.getWidth(), height = source.getHeight();
        int gw = generated.getWidth(), gh = generated.getHeight();
        int[] gen = generated.getRGB(0, 0, gw, gh, null, 0, gw);
        double sx = gw / (double) frame.width, sy = gh / (double) frame.height;

        // Models often shift the whole image's tone a little; match the edit to the original just outside the mask.
        int inner = (grow + feather) * UNIT, outer = (grow + feather + ring(width, height)) * UNIT;
        long[] sum = new long[3];
        long count = 0;
        int[] row = new int[width];
        for (int y = frame.y; y < frame.y + frame.height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int x = frame.x; x < frame.x + frame.width; x++) {
                int d = distance[y * width + x];
                if (d <= inner || d > outer) continue;
                int edit = sample(gen, gw, gh, (x - frame.x + 0.5) * sx - 0.5, (y - frame.y + 0.5) * sy - 0.5);
                for (int c = 0; c < 3; c++) sum[c] += channel(row[x], c) - channel(edit, c);
                count++;
            }
        }
        int[] shift = new int[3];
        if (count > 0) for (int c = 0; c < 3; c++)
            shift[c] = (int) Math.max(-MAX_COLOR_SHIFT, Math.min(MAX_COLOR_SHIFT, Math.round(sum[c] / (double) count)));

        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage result = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            boolean inFrame = y >= frame.y && y < frame.y + frame.height;
            if (inFrame) for (int x = frame.x; x < frame.x + frame.width; x++) {
                double w = weight(distance[y * width + x], grow, feather);
                if (w <= 0) continue;
                int edit = sample(gen, gw, gh, (x - frame.x + 0.5) * sx - 0.5, (y - frame.y + 0.5) * sy - 0.5);
                int pixel = row[x] & 0xFF000000;
                for (int c = 0; c < 3; c++) {
                    int value = (int) Math.round(w * clamp(channel(edit, c) + shift[c]) + (1 - w) * channel(row[x], c));
                    pixel |= clamp(value) << (16 - 8 * c);
                }
                row[x] = pixel;
            }
            result.setRGB(0, y, width, 1, row, 0, width);
        }
        return result;
    }

    /**
     * Share of the edited area the model filled with near-black although nothing around it is dark: a known failure
     * where a hole or flat dark patch is painted instead of the surface. 0 when the surroundings are dark themselves.
     */
    static double darkPatchShare(BufferedImage source, BufferedImage generated, short[] distance, int grow, int feather,
                                 Rectangle frame) {
        int width = source.getWidth(), height = source.getHeight();
        int gw = generated.getWidth(), gh = generated.getHeight();
        int[] gen = generated.getRGB(0, 0, gw, gh, null, 0, gw);
        double sx = gw / (double) frame.width, sy = gh / (double) frame.height;
        int inner = (grow + feather) * UNIT, outer = (grow + feather + ring(width, height)) * UNIT;
        long masked = 0, dark = 0, around = 0, darkAround = 0;
        int[] row = new int[width];
        for (int y = frame.y; y < frame.y + frame.height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int x = frame.x; x < frame.x + frame.width; x++) {
                int d = distance[y * width + x];
                if (d <= grow * UNIT) {
                    masked++;
                    int edit = gen[clampIndex((y - frame.y + 0.5) * sy, gh) * gw + clampIndex((x - frame.x + 0.5) * sx, gw)];
                    if (luma(edit) < DARK_LUMA) dark++;
                } else if (d > inner && d <= outer) {
                    around++;
                    if (luma(row[x]) < DARK_LUMA) darkAround++;
                }
            }
        }
        if (masked == 0 || around == 0 || darkAround > around * DARK_SURROUNDING_SHARE) return 0;
        return dark / (double) masked;
    }

    static final double DARK_PATCH_LIMIT = 0.15;
    private static final int DARK_LUMA = 40;
    private static final double DARK_SURROUNDING_SHARE = 0.05;

    /**
     * How much of a text line's edge strength (luma steps between neighbouring pixels inside the line) is left
     * after the edit: near 0 when the writing was erased to a plain surface, near 1 when the editor left it as it
     * was. 0 for a line with almost no edges in the original, since there is nothing to check.
     */
    static double edgeRatio(BufferedImage source, BufferedImage result, PreparedEditImage.TextPart part) {
        Rectangle box = part.bounds();
        if (box.width < 2 || box.height < 2) return 0;
        int[] before = source.getRGB(box.x, box.y, box.width, box.height, null, 0, box.width);
        int[] after = result.getRGB(box.x, box.y, box.width, box.height, null, 0, box.width);
        double edgesBefore = 0, edgesAfter = 0;
        long count = 0;
        for (int y = 1; y < box.height; y++) for (int x = 1; x < box.width; x++) {
            if (!part.path().contains(box.x + x + 0.5, box.y + y + 0.5)) continue;
            int i = y * box.width + x;
            edgesBefore += Math.abs(luma(before[i]) - luma(before[i - 1])) + Math.abs(luma(before[i]) - luma(before[i - box.width]));
            edgesAfter += Math.abs(luma(after[i]) - luma(after[i - 1])) + Math.abs(luma(after[i]) - luma(after[i - box.width]));
            count++;
        }
        if (count == 0 || edgesBefore / count < MIN_LINE_EDGES) return 0;
        return edgesAfter / edgesBefore;
    }

    /** Above this {@link #edgeRatio} a line still reads: erased lines measured at most 0.31, skipped ones 0.72+. */
    static final double UNERASED_EDGE_RATIO = 0.5;
    private static final double MIN_LINE_EDGES = 3;

    /** Lines whose boxes come within {@code gap} pixels of each other, chained, as one group each. */
    static List<List<PreparedEditImage.TextPart>> groups(List<PreparedEditImage.TextPart> parts, int gap) {
        int[] parent = new int[parts.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < parts.size(); i++) {
            Rectangle near = parts.get(i).bounds();
            near = new Rectangle(near.x - gap, near.y - gap, near.width + 2 * gap, near.height + 2 * gap);
            for (int j = i + 1; j < parts.size(); j++)
                if (near.intersects(parts.get(j).bounds())) parent[root(parent, j)] = root(parent, i);
        }
        var grouped = new java.util.LinkedHashMap<Integer, List<PreparedEditImage.TextPart>>();
        for (int i = 0; i < parts.size(); i++) grouped.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(parts.get(i));
        return List.copyOf(grouped.values());
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    private static int clampIndex(double position, int size) { return Math.max(0, Math.min(size - 1, (int) position)); }

    private static int luma(int rgb) { return (299 * channel(rgb, 0) + 587 * channel(rgb, 1) + 114 * channel(rgb, 2)) / 1000; }

    /** Bicubic (Catmull-Rom) sample of an RGB pixel array at fractional coordinates, edges clamped. */
    private static int sample(int[] pixels, int width, int height, double fx, double fy) {
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        double[] wx = cubic(tx), wy = cubic(ty);
        double[] acc = new double[3];
        for (int j = 0; j < 4; j++) {
            int yy = Math.max(0, Math.min(height - 1, y0 - 1 + j));
            for (int i = 0; i < 4; i++) {
                int xx = Math.max(0, Math.min(width - 1, x0 - 1 + i));
                int p = pixels[yy * width + xx];
                double w = wx[i] * wy[j];
                for (int c = 0; c < 3; c++) acc[c] += w * channel(p, c);
            }
        }
        return (clamp((int) Math.round(acc[0])) << 16) | (clamp((int) Math.round(acc[1])) << 8) | clamp((int) Math.round(acc[2]));
    }

    private static double[] cubic(double t) {
        double t2 = t * t, t3 = t2 * t;
        return new double[]{
                (-t3 + 2 * t2 - t) / 2, (3 * t3 - 5 * t2 + 2) / 2, (-3 * t3 + 4 * t2 + t) / 2, (t3 - t2) / 2};
    }

    private static int channel(int rgb, int c) { return (rgb >> (16 - 8 * c)) & 0xFF; }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
