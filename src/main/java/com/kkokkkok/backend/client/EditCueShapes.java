package com.kkokkkok.backend.client;

import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * The areas to edit for one cue's text lines, in pixels of a {@code width}x{@code height} photo. Lines lying within
     * {@value #JOIN_GAP} line heights of each other become one area, their convex hull: OCR often reads only the small
     * lines of a sign (a slogan above, a year below) and not the stylized name between them, and a board whose
     * lines are all erased one by one keeps fragments of the icons and frames between them. A group whose hull would
     * be far larger than its lines ({@value #MAX_JOIN_SPREAD}x) stays line by line.
     */
    static List<Path2D.Double> joinNearLines(List<Shape> lines, int width, int height) {
        int count = lines.size();
        double[][][] points = new double[count][][];
        Rectangle2D[] bounds = new Rectangle2D[count];
        double[] area = new double[count], thickness = new double[count];
        for (int i = 0; i < count; i++) {
            double[][] corners = lines.get(i).corners();
            points[i] = new double[corners.length][];
            double longest = 0;
            for (int k = 0; k < corners.length; k++) {
                points[i][k] = new double[]{corners[k][0] * width, corners[k][1] * height};
            }
            for (int k = 0; k < corners.length; k++) {
                double[] a = points[i][k], b = points[i][(k + 1) % corners.length];
                longest = Math.max(longest, Math.hypot(b[0] - a[0], b[1] - a[1]));
            }
            area[i] = area(points[i]);
            thickness[i] = longest > 0 ? area[i] / longest : 0;
            bounds[i] = path(points[i]).getBounds2D();
        }
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) parent[i] = i;
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++)
            if (gap(bounds[i], bounds[j]) <= JOIN_GAP * Math.max(thickness[i], thickness[j]))
                parent[root(parent, j)] = root(parent, i);
        var groups = new LinkedHashMap<Integer, List<Integer>>();
        for (int i = 0; i < count; i++) groups.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(i);
        List<Path2D.Double> areas = new ArrayList<>();
        for (List<Integer> group : groups.values()) {
            List<double[]> all = new ArrayList<>();
            double lineArea = 0;
            for (int i : group) { all.addAll(List.of(points[i])); lineArea += area[i]; }
            double[][] hull = hull(all);
            double tallest = 0;
            for (int i : group) tallest = Math.max(tallest, thickness[i]);
            if (group.size() > 1 && area(hull) <= MAX_JOIN_SPREAD * lineArea) areas.add(padded(path(hull), tallest));
            else for (int i : group) areas.add(padded(path(points[i]), thickness[i]));
        }
        return areas;
    }

    private static final double JOIN_GAP = 3;
    private static final double MAX_JOIN_SPREAD = 8;
    private static final double LINE_PAD = 0.2;

    /**
     * {@code area} grown by {@value #LINE_PAD} of its line height on every side. Raised and illuminated letters cast
     * shadows and leave outlines a little outside the OCR box, which a margin fixed to the photo size misses on large
     * signs; growing with the letters takes them in.
     */
    private static Path2D.Double padded(Path2D.Double area, double lineHeight) {
        double pad = LINE_PAD * lineHeight;
        if (pad < 0.5) return area;
        var grown = new Area(area);
        grown.add(new Area(new BasicStroke((float) (2 * pad), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(area)));
        return new Path2D.Double(grown);
    }

    private static double gap(Rectangle2D a, Rectangle2D b) {
        double dx = Math.max(0, Math.max(a.getMinX(), b.getMinX()) - Math.min(a.getMaxX(), b.getMaxX()));
        double dy = Math.max(0, Math.max(a.getMinY(), b.getMinY()) - Math.min(a.getMaxY(), b.getMaxY()));
        return Math.hypot(dx, dy);
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    private static double area(double[][] points) {
        double twice = 0;
        for (int k = 0; k < points.length; k++) {
            double[] a = points[k], b = points[(k + 1) % points.length];
            twice += a[0] * b[1] - b[0] * a[1];
        }
        return Math.abs(twice) / 2;
    }

    /** Convex hull (monotone chain), counter-clockwise. */
    private static double[][] hull(List<double[]> points) {
        var sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = sorted.size();
        if (n < 3) return sorted.toArray(double[][]::new);
        double[][] chain = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(chain[k - 2], chain[k - 1], sorted.get(i)) <= 0) k--;
            chain[k++] = sorted.get(i);
        }
        for (int i = n - 2, lower = k + 1; i >= 0; i--) {
            while (k >= lower && cross(chain[k - 2], chain[k - 1], sorted.get(i)) <= 0) k--;
            chain[k++] = sorted.get(i);
        }
        return java.util.Arrays.copyOf(chain, Math.max(1, k - 1));
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static Path2D.Double path(double[][] points) {
        Path2D.Double path = new Path2D.Double();
        for (int k = 0; k < points.length; k++) {
            if (k == 0) path.moveTo(points[k][0], points[k][1]);
            else path.lineTo(points[k][0], points[k][1]);
        }
        path.closePath();
        return path;
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
