package com.kkokkkok.backend.client;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * The upright photo and the mask sent to the editor, plus what is needed to put the edit back: the distance of
 * every pixel to the selected clue shapes and the margin/fade widths ({@link EditComposite}).
 */
record PreparedEditImage(byte[] imagePng, byte[] maskPng, int width, int height,
                         List<Map<String, Object>> visualCues,
                         BufferedImage source, int[] distance, int grow, int feather) {}
