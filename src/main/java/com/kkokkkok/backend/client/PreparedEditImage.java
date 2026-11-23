package com.kkokkkok.backend.client;

import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * The upright photo and the mask sent to the editor, plus what is needed to put the edit back: the distance of
 * every pixel to the selected clue shapes, the margin/fade widths ({@link EditComposite}), and the part of
 * the photo ({@code frame}, original pixels) that the editor's image shows. {@code textParts} are the selected
 * text lines, so lines the editor left readable can be found and sent again.
 */
record PreparedEditImage(byte[] imagePng, byte[] maskPng, int width, int height,
                         List<Map<String, Object>> visualCues,
                         BufferedImage source, short[] distance, int grow, int feather,
                         Rectangle frame, List<TextPart> textParts) {

    /** One selected text line (or a text cue's box) in pixels of the upright photo. */
    record TextPart(Map<String, Object> cue, Path2D path, Rectangle bounds) {}
}
