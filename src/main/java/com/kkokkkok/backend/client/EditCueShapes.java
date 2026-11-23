package com.kkokkkok.backend.client;

import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The exact area of a selected cue, from the analysis server's {@code riskFactors[].regions[]}: a text
 * region carries a {@code polygon} (OCR line corners), an object region a {@code mask} (run lengths over
 * the whole photo at reduced size). All coordinates are fractions of the upright photo, like the bbox.
 *
 * <p>The cue's own bbox is the one area the analysis server chose for it (for text, the lines of one
 * quoted name joined), so only regions lying inside that box are taken: a detector box that answered
 * another phrase elsewhere in the photo is not what the user selected. Two kinds reach past that box and are
 * taken too: a text line the analysis verified by OCR as part of the cue (a station sign's other names, the same
 * shop name on a poster), and an object region that covers the box (a sign's post below its board). A cue with
 * no usable shape is edited by its box, as before.
 */
final class EditCueShapes {
    private static final double TOLERANCE = 0.002;
    private static final long MAX_MASK_CELLS = 1024L * 1024L;
    private static final double OBJECT_COVER = 0.9;

    interface Filler { void fill(Graphics2D graphics, int width, int height); }

    /** One area to edit: a text line's corners, or an object mask's filler. Lines are checked after the edit. */
    record Shape(Filler filler, double[][] corners) {
        void fill(Graphics2D graphics, int width, int height) {
            if (corners != null) graphics.fill(path(width, height));
            else filler.fill(graphics, width, height);
        }

        boolean text() { return corners != null; }

        /** The text line's outline in pixels of a {@code width}x{@code height} photo; only for {@link #text()}. */
        Path2D.Double path(int width, int height) {
            Path2D.Double path = new Path2D.Double();
            for (int index = 0; index < corners.length; index++) {
                if (index == 0) path.moveTo(corners[index][0] * width, corners[index][1] * height);
                else path.lineTo(corners[index][0] * width, corners[index][1] * height);
            }
            path.closePath();
            return path;
        }
    }

    private EditCueShapes() {}

    /** Empty when the cue has only its box, or every shape it has is malformed. */
    static List<Shape> of(Map<String, Object> cue, double[] bbox) {
        List<Shape> shapes = new ArrayList<>();
        if (!(cue.get("regions") instanceof List<?> regions)) return shapes;
        for (Object item : regions) {
            if (!(item instanceof Map<?, ?> region)) continue;
            double[] box = box(region.get("bbox"));
            if (box == null) continue;
            boolean inside = box[0] >= bbox[0] - TOLERANCE && box[1] >= bbox[1] - TOLERANCE
                    && box[2] <= bbox[2] + TOLERANCE && box[3] <= bbox[3] + TOLERANCE;
            String kind = String.valueOf(region.get("kind"));
            boolean verifiedText = "text".equals(kind) && Boolean.TRUE.equals(region.get("verified"));
            if (!inside && !verifiedText && !("object".equals(kind) && covers(box, bbox))) continue;
            Shape shape = switch (kind) {
                case "text" -> polygon(region.get("polygon"));
                case "object" -> mask(region.get("mask"));
                default -> null;
            };
            if (shape != null) shapes.add(shape);
        }
        return shapes;
    }

    /** Whether {@code region} covers almost all of {@code bbox}. */
    private static boolean covers(double[] region, double[] bbox) {
        double width = Math.min(region[2], bbox[2]) - Math.max(region[0], bbox[0]);
        double height = Math.min(region[3], bbox[3]) - Math.max(region[1], bbox[1]);
        if (width <= 0 || height <= 0) return false;
        return width * height >= OBJECT_COVER * (bbox[2] - bbox[0]) * (bbox[3] - bbox[1]);
    }

    private static double[] box(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() != 4) return null;
        double[] box = new double[4];
        for (int index = 0; index < 4; index++) {
            if (!(values.get(index) instanceof Number number) || !Double.isFinite(number.doubleValue())) return null;
            box[index] = number.doubleValue();
        }
        return box;
    }

    private static Shape polygon(Object raw) {
        if (!(raw instanceof List<?> points) || points.size() < 3 || points.size() > 64) return null;
        double[][] corners = new double[points.size()][];
        for (int index = 0; index < corners.length; index++) {
            if (!(points.get(index) instanceof List<?> point) || point.size() != 2
                    || !(point.get(0) instanceof Number x) || !(point.get(1) instanceof Number y)
                    || !Double.isFinite(x.doubleValue()) || !Double.isFinite(y.doubleValue())) return null;
            // OCR corners can fall a hair outside the photo.
            corners[index] = new double[]{clamp(x.doubleValue()), clamp(y.doubleValue())};
        }
        return new Shape(null, corners);
    }

    /** {@code {size:[height,width], counts:[run lengths, row by row, starting with unset cells]}} */
    private static Shape mask(Object raw) {
        if (!(raw instanceof Map<?, ?> mask) || !(mask.get("size") instanceof List<?> size) || size.size() != 2
                || !(mask.get("counts") instanceof List<?> counts)) return null;
        if (!(size.get(0) instanceof Integer rows) || !(size.get(1) instanceof Integer columns)
                || rows < 1 || columns < 1 || (long) rows * columns > MAX_MASK_CELLS) return null;
        int[] runs = new int[counts.size()];
        long total = 0;
        for (int index = 0; index < runs.length; index++) {
            if (!(counts.get(index) instanceof Integer run) || run < 0) return null;
            runs[index] = run;
            total += run;
        }
        if (total != (long) rows * columns) return null;
        return new Shape((graphics, width, height) -> {
            int position = 0;
            for (int index = 0; index < runs.length; index++) {
                // Odd runs are the set cells. A run goes on into the next row: it is cut at each row end.
                for (int start = position, end = position + runs[index]; index % 2 == 1 && start < end; ) {
                    int row = start / columns, from = start % columns, to = Math.min(columns, from + (end - start));
                    // Every pixel a set cell touches is covered, so no sliver is left between cells.
                    int left = (int) Math.floor((double) from * width / columns);
                    int right = (int) Math.ceil((double) to * width / columns);
                    int top = (int) Math.floor((double) row * height / rows);
                    int bottom = (int) Math.ceil((double) (row + 1) * height / rows);
                    graphics.fillRect(left, top, right - left, bottom - top);
                    start += to - from;
                }
                position += runs[index];
            }
        }, null);
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
